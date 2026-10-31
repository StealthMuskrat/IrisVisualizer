package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Explicit advisory file comparison; one active read and one latest pending immutable capture. */
public final class AsyncFileComparison implements AutoCloseable {
    public enum Phase { IDLE, COMPARING, READY, FAILED, CLOSED }
    public record Snapshot(long request,String path,Phase phase,long applied,long disk,FileComparison.Report report,String error){}
    @FunctionalInterface interface Reader {FileComparison.Report compare(FileComparison.Source source,BooleanSupplier cancelled,FileComparison.Progress progress)throws Exception;}
    private static final class Job {
        final long request;final FileComparison.Source source;final AtomicBoolean cancelled=new AtomicBoolean();volatile long applied,disk;
        Job(long request,FileComparison.Source source){this.request=request;this.source=source;}void progress(long applied,long disk){this.applied=applied;this.disk=disk;}
    }
    private final Object lock=new Object();private final Reader reader;private final Thread worker;
    private PackWorkspace owner;private String path;private long content,saved,generation;private Job active,pending;private boolean closed;
    private Snapshot snapshot=new Snapshot(0,null,Phase.IDLE,0,0,null,null);
    public AsyncFileComparison(){this(FileComparison::compare,r->new Thread(r,"iris-file-comparison"));}
    AsyncFileComparison(Reader reader,ThreadFactory threads){this.reader=Objects.requireNonNull(reader);worker=Objects.requireNonNull(threads).newThread(this::run);if(worker==null)throw new IllegalArgumentException("File comparison thread factory returned no thread");worker.setDaemon(true);worker.start();}
    private void invalidate(){generation++;if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;}
    public Snapshot start(PackWorkspace current,String selected)throws IOException{
        Objects.requireNonNull(current);var source=current.captureFileComparison(selected);long stamp=current.contentRevision(selected),baseline=current.saveRevision();
        synchronized(lock){if(closed)throw new IllegalStateException("File comparison is closed");invalidate();owner=current;path=selected;content=stamp;saved=baseline;pending=new Job(generation,source);snapshot=new Snapshot(generation,path,Phase.COMPARING,0,0,null,null);lock.notifyAll();return snapshot;}
    }
    public Snapshot poll(PackWorkspace current){Objects.requireNonNull(current);synchronized(lock){if(closed)return snapshot;
        if(owner!=null && (owner!=current || !current.paths().contains(path) || current.contentRevision(path)!=content || current.saveRevision()!=saved)){invalidate();owner=null;path=null;snapshot=new Snapshot(generation,null,Phase.IDLE,0,0,null,null);}
        var job=active!=null && active.request==generation ? active : pending;if(snapshot.phase()==Phase.COMPARING && job!=null)return new Snapshot(generation,path,Phase.COMPARING,job.applied,job.disk,null,null);return snapshot;
    }}
    public void clear(){synchronized(lock){if(closed)return;invalidate();owner=null;path=null;snapshot=new Snapshot(generation,null,Phase.IDLE,0,0,null,null);}worker.interrupt();}
    private void run(){while(true){Job job;synchronized(lock){Thread.interrupted();while(!closed && pending==null)try{lock.wait();}catch(InterruptedException interrupted){if(closed)return;}if(closed)return;active=job=pending;pending=null;}
        FileComparison.Report report=null;String error=null;
        try{report=Objects.requireNonNull(reader.compare(job.source,job.cancelled::get,job::progress),"File comparison returned no report");if(!report.path().equals(job.source.path) || !Objects.equals(report.directory(),job.source.directory))throw new IOException("Comparison report belongs to another resource");}
        catch(CancellationException cancelled){error="File comparison cancelled; Refresh explicitly to retry.";}
        catch(OutOfMemoryError exhausted){error="Not enough memory to compare this resource; Clear and retry explicitly.";}
        catch(Exception failure){String detail=failure.getMessage();if(detail==null || detail.isBlank())detail="File comparison failed ("+failure.getClass().getSimpleName()+")";error=InspectorText.label(detail,512);}
        synchronized(lock){active=null;if(!closed && !job.cancelled.get() && job.request==generation)snapshot=new Snapshot(generation,path,error==null ? Phase.READY : Phase.FAILED,job.applied,job.disk,error==null ? report : null,error);}job=null;report=null;
    }}
    @Override public void close(){synchronized(lock){if(closed)return;closed=true;invalidate();owner=null;path=null;snapshot=new Snapshot(generation,null,Phase.CLOSED,0,0,null,null);lock.notifyAll();}worker.interrupt();}
}
