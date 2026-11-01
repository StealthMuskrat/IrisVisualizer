package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One active selected-source worker and one replaceable pending request; UI owns selection and drafts. */
final class AsyncJsonSourceReview implements AutoCloseable {
    enum Phase {IDLE,PREPARING,READY,FAILED,CLOSED}
    record Snapshot(long request,String path,Phase phase,JsonSourceReview.Report report,String error){}
    @FunctionalInterface interface Scanner {JsonSourceReview.Report inspect(JsonSourceReview.Source source,BooleanSupplier cancelled)throws Exception;}
    @FunctionalInterface interface Capture {JsonSourceReview.Source capture(PackWorkspace workspace,String path,SourceMatch.Location location);}
    private record Job(long request,JsonSourceReview.Source source,AtomicBoolean cancelled){}
    private final Object lock=new Object();private final Scanner scanner;private final Capture capture;private final Thread worker;
    private PackWorkspace owner;private String path;private long stamp=-1,generation;private boolean closed;private Job active,pending;
    private Snapshot snapshot=new Snapshot(0,null,Phase.IDLE,null,null);
    AsyncJsonSourceReview(){this(JsonSourceReview::inspect,r->new Thread(r,"iris-json-source-review"),PackWorkspace::captureJsonSource);}
    AsyncJsonSourceReview(Scanner scanner,ThreadFactory threads,Capture capture){this.scanner=Objects.requireNonNull(scanner);this.capture=Objects.requireNonNull(capture);worker=Objects.requireNonNull(threads).newThread(this::run);if(worker==null)throw new IllegalArgumentException("JSON source worker factory returned no thread");worker.setDaemon(true);worker.start();}
    private void invalidate(){generation++;if(active!=null)active.cancelled().set(true);if(pending!=null)pending.cancelled().set(true);pending=null;}
    private static long stamp(PackWorkspace current,String selected){return selected!=null && current.paths().contains(selected)?current.contentRevision(selected):-1;}
    Snapshot select(PackWorkspace current,String selected){Objects.requireNonNull(current);synchronized(lock){if(closed)throw new IllegalStateException("JSON source review is closed");if(owner==current && Objects.equals(path,selected) && stamp==stamp(current,selected) && snapshot.phase()!=Phase.IDLE)return snapshot;}return start(current,selected,null);}
    Snapshot start(PackWorkspace current,String selected,SourceMatch.Location location){
        Objects.requireNonNull(current);long request;long content=stamp(current,selected);
        synchronized(lock){if(closed)throw new IllegalStateException("JSON source review is closed");invalidate();owner=current;path=selected;stamp=content;request=generation;snapshot=new Snapshot(request,path,Phase.PREPARING,null,null);}
        JsonSourceReview.Source source;
        try{source=Objects.requireNonNull(capture.capture(current,selected,location),"JSON source capture returned no source");}
        catch(OutOfMemoryError exhausted){return failed(request,"Not enough memory to capture JSON source metadata; retry explicitly.");}
        catch(Exception failure){return failed(request,error(failure));}
        synchronized(lock){if(!closed && request==generation){pending=new Job(request,source,new AtomicBoolean());lock.notifyAll();}return snapshot;}
    }
    private Snapshot failed(long request,String detail){synchronized(lock){if(!closed && request==generation)snapshot=new Snapshot(request,path,Phase.FAILED,null,detail);return snapshot;}}
    Snapshot poll(PackWorkspace current,String selected){Objects.requireNonNull(current);synchronized(lock){if(!closed && owner!=null && (owner!=current || !Objects.equals(path,selected) || stamp!=stamp(current,selected))){invalidate();owner=null;path=null;stamp=-1;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null);}return snapshot;}}
    void clear(){synchronized(lock){if(closed)return;invalidate();owner=null;path=null;stamp=-1;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null);}worker.interrupt();}
    private static String error(Exception failure){String message=failure.getMessage();return InspectorText.label(message==null || message.isBlank()?"JSON source preparation failed ("+failure.getClass().getSimpleName()+")":message,512);}
    private void run(){
        while(true){Job job;synchronized(lock){Thread.interrupted();while(!closed && pending==null)try{lock.wait();}catch(InterruptedException interrupted){if(closed)return;}if(closed)return;active=job=pending;pending=null;}
            JsonSourceReview.Report report=null;String problem=null;
            try{report=Objects.requireNonNull(scanner.inspect(job.source(),job.cancelled()::get),"JSON source scanner returned no report");if(!Objects.equals(report.path(),job.source().path) || report.bytes()!=job.source().bytes || !Objects.equals(report.location(),job.source().location))throw new IllegalArgumentException("JSON source scanner returned another source");}
            catch(CancellationException cancelled){problem="JSON source preparation cancelled; retry explicitly.";}
            catch(OutOfMemoryError exhausted){problem="Not enough memory to prepare JSON source; clear the browser and retry explicitly.";}
            catch(Exception failure){problem=error(failure);}
            synchronized(lock){active=null;if(!closed && !job.cancelled().get() && job.request()==generation)snapshot=new Snapshot(generation,job.source().path,problem==null?Phase.READY:Phase.FAILED,problem==null?report:null,problem);}
            job=null;report=null;
        }
    }
    @Override public void close(){synchronized(lock){if(closed)return;closed=true;invalidate();owner=null;path=null;stamp=-1;snapshot=new Snapshot(generation,null,Phase.CLOSED,null,null);lock.notifyAll();}worker.interrupt();}
}
