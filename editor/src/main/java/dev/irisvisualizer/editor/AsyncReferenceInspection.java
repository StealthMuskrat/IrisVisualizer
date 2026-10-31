package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned explicit selected-resource inspections. One active scan and one replaceable latest pending source. */
public final class AsyncReferenceInspection implements AutoCloseable {
    public enum Phase {IDLE,SCANNING,READY,FAILED,CLOSED}
    public record Snapshot(long request,String path,Phase phase,ReferenceInspection.Report report,String error){}
    @FunctionalInterface interface Scanner {ReferenceInspection.Report inspect(ReferenceInspection.Source source,BooleanSupplier cancelled)throws Exception;}
    private record Job(long request,ReferenceInspection.Source source,AtomicBoolean cancelled){}
    private final Object lock=new Object();private final Scanner scanner;private final Thread worker;
    private PackWorkspace owner;private String path;private long stamp,resources,generation;private boolean closed;
    private Job active,pending;
    private Snapshot snapshot=new Snapshot(0,null,Phase.IDLE,null,null);
    public AsyncReferenceInspection(){this(ReferenceInspection::inspect,r->new Thread(r,"iris-resource-link-inspection"));}
    AsyncReferenceInspection(Scanner scanner,ThreadFactory threads){this.scanner=Objects.requireNonNull(scanner);worker=Objects.requireNonNull(threads).newThread(this::run);if(worker==null)throw new IllegalArgumentException("Resource link worker factory returned no thread");worker.setDaemon(true);worker.start();}
    private void invalidate(){generation++;if(active!=null)active.cancelled().set(true);if(pending!=null)pending.cancelled().set(true);pending=null;}
    /** Capture path metadata and borrow the immutable selected array; cloning/decoding/parsing happen on the worker. */
    public Snapshot start(PackWorkspace current,String selected)throws IOException {
        Objects.requireNonNull(current);synchronized(lock){if(closed)throw new IllegalStateException("Resource link inspection is closed");}
        ReferenceInspection.Source source;
        try{source=current.captureReferenceInspection(selected);}catch(OutOfMemoryError exhausted){throw new IOException("Not enough memory to capture resource link paths; clear the inspection and retry explicitly.");}
        long content=current.contentRevision(selected),structure=current.resourceRevision();
        synchronized(lock){if(closed)throw new IllegalStateException("Resource link inspection is closed");invalidate();owner=current;path=selected;stamp=content;resources=structure;pending=new Job(generation,source,new AtomicBoolean());snapshot=new Snapshot(generation,path,Phase.SCANNING,null,null);lock.notifyAll();return snapshot;}
    }
    /** Metadata-only invalidation, including hidden/collapsed inspectors. Never schedules a scan. */
    public Snapshot poll(PackWorkspace current,String selected){
        Objects.requireNonNull(current);synchronized(lock){if(closed)return snapshot;
            if(owner!=null && (owner!=current || !Objects.equals(path,selected) || resources!=current.resourceRevision() || !current.paths().contains(path) || current.contentRevision(path)!=stamp)){
                invalidate();owner=null;path=null;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null);
            }
            return snapshot;
        }
    }
    public void clear(){synchronized(lock){if(closed)return;invalidate();owner=null;path=null;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null);}worker.interrupt();}
    private void run(){
        while(true){Job job;synchronized(lock){Thread.interrupted();while(!closed && pending==null)try{lock.wait();}catch(InterruptedException interrupted){if(closed)return;}if(closed)return;active=job=pending;pending=null;}
            ReferenceInspection.Report report=null;String error=null;
            try{report=Objects.requireNonNull(scanner.inspect(job.source(),job.cancelled()::get),"Resource link scanner returned no report");if(!report.path().equals(job.source().path) || report.bytes()!=job.source().bytes())throw new IOException("Resource link scanner returned another source");}
            catch(CancellationException cancelled){error="Resource link inspection cancelled; inspect again explicitly.";}
            catch(OutOfMemoryError exhausted){error="Not enough memory to inspect resource links; clear the inspection and retry explicitly.";}
            catch(Exception failure){String detail=failure.getMessage();if(detail==null || detail.isBlank())detail="Resource link inspection failed ("+failure.getClass().getSimpleName()+")";error=InspectorText.label(detail,512);}
            synchronized(lock){active=null;if(!closed && !job.cancelled().get() && job.request()==generation)snapshot=new Snapshot(generation,job.source().path,error==null ? Phase.READY : Phase.FAILED,error==null ? report : null,error);}
            job=null;report=null;
        }
    }
    @Override public void close(){synchronized(lock){if(closed)return;closed=true;invalidate();owner=null;path=null;snapshot=new Snapshot(generation,null,Phase.CLOSED,null,null);lock.notifyAll();}worker.interrupt();}
}
