package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Selected applied dimension metadata only: one active read, one replaceable pending source. */
final class PreviewDimensionContext implements AutoCloseable {
    static final int MAX_BYTES=1_000_000;
    enum Phase {IDLE,READING,READY,FAILED,CLOSED}
    record Report(String path,long stamp,int bytes,DimensionHeights.PreviewRange height){}
    record Snapshot(long request,String path,Phase phase,Report report,String error){}
    static final class Source {
        final String path;final long stamp;final int bytes;private final byte[] data;
        Source(String path,long stamp,byte[] data){
            if(!DimensionFields.dimension(path) || stamp<0)throw new IllegalArgumentException("Choose an applied top-level dimension JSON.");
            this.path=path;this.stamp=stamp;bytes=Objects.requireNonNull(data).length;this.data=bytes<=MAX_BYTES?data:null;
        }
        int retainedBytes(){return data==null?0:bytes;}
    }
    @FunctionalInterface interface Reader {Report read(Source source,BooleanSupplier cancelled)throws Exception;}
    private record Job(long request,Source source,AtomicBoolean cancelled){}
    private final Object lock=new Object();private final Reader reader;private final Thread worker;
    private PackWorkspace owner;private String path;private long stamp=-1,generation;private boolean closed;private Job active,pending;
    private Snapshot snapshot=new Snapshot(0,null,Phase.IDLE,null,null);
    PreviewDimensionContext(){this(PreviewDimensionContext::read,r->new Thread(r,"iris-preview-dimension-context"));}
    PreviewDimensionContext(Reader reader,ThreadFactory threads){this.reader=Objects.requireNonNull(reader);worker=Objects.requireNonNull(threads).newThread(this::run);if(worker==null)throw new IllegalArgumentException("Preview context factory returned no thread.");worker.setDaemon(true);worker.start();}
    private static long stamp(PackWorkspace current,String selected){return selected!=null && DimensionFields.dimension(selected) && current.paths().contains(selected)?current.contentRevision(selected):-1;}
    private void invalidate(){generation++;if(active!=null)active.cancelled().set(true);if(pending!=null)pending.cancelled().set(true);pending=null;}
    /** Metadata checks only on the UI thread; unchanged failures wait for an edit or explicit retry. */
    Snapshot select(PackWorkspace current,String selected){
        Objects.requireNonNull(current);long content=stamp(current,selected);
        synchronized(lock){
            if(closed)return snapshot;
            if(content<0){if(owner!=null || snapshot.phase()!=Phase.IDLE){invalidate();owner=null;path=null;stamp=-1;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null);}return snapshot;}
            if(owner==current && Objects.equals(path,selected) && stamp==content)return snapshot;
            invalidate();owner=current;path=selected;stamp=content;snapshot=new Snapshot(generation,path,Phase.READING,null,null);
            try{pending=new Job(generation,current.capturePreviewDimension(path),new AtomicBoolean());lock.notifyAll();}
            catch(OutOfMemoryError exhausted){snapshot=new Snapshot(generation,path,Phase.FAILED,null,"Not enough memory to capture preview height metadata. Retry explicitly.");}
            catch(Exception failure){snapshot=new Snapshot(generation,path,Phase.FAILED,null,error(failure));}
            return snapshot;
        }
    }
    /** Used while the preview panel is hidden; invalidates old output without scheduling another read. */
    Snapshot poll(PackWorkspace current,String selected){
        Objects.requireNonNull(current);synchronized(lock){if(!closed && owner!=null && (owner!=current || !Objects.equals(path,selected) || stamp!=stamp(current,selected))){invalidate();owner=null;path=null;stamp=-1;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null);}return snapshot;}
    }
    static Report read(Source source,BooleanSupplier cancelled){
        check(cancelled);if(source.data==null)throw new IllegalArgumentException("Dimension JSON exceeds the 1 MB preview-height metadata limit; no payload was retained and no height is assumed.");
        var document=new LosslessJson(source.data);check(cancelled);var height=DimensionHeights.previewRange(document);check(cancelled);return new Report(source.path,source.stamp,source.bytes,height);
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Preview height read cancelled.");}
    private static String error(Exception failure){String message=failure.getMessage();return InspectorText.label(message==null || message.isBlank()?"Preview height read failed ("+failure.getClass().getSimpleName()+")":message,512);}
    void clear(){synchronized(lock){if(closed)return;invalidate();owner=null;path=null;stamp=-1;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null);}worker.interrupt();}
    private void run(){
        while(true){Job job;synchronized(lock){Thread.interrupted();while(!closed && pending==null)try{lock.wait();}catch(InterruptedException interrupted){if(closed)return;}if(closed)return;active=job=pending;pending=null;}
            Report report=null;String problem=null;
            try{report=Objects.requireNonNull(reader.read(job.source(),job.cancelled()::get),"Preview height reader returned no report.");if(!Objects.equals(report.path(),job.source().path) || report.stamp()!=job.source().stamp || report.bytes()!=job.source().bytes || report.height()==null)throw new IllegalArgumentException("Preview height reader returned another source.");}
            catch(CancellationException cancelled){problem="Preview height read cancelled. Retry explicitly.";}
            catch(OutOfMemoryError exhausted){problem="Not enough memory to read preview height metadata. Retry explicitly.";}
            catch(Exception failure){problem=error(failure);}
            synchronized(lock){active=null;if(!closed && !job.cancelled().get() && job.request()==generation)snapshot=new Snapshot(generation,job.source().path,problem==null?Phase.READY:Phase.FAILED,problem==null?report:null,problem);}
            job=null;report=null;
        }
    }
    @Override public void close(){synchronized(lock){if(closed)return;closed=true;invalidate();owner=null;path=null;stamp=-1;snapshot=new Snapshot(generation,null,Phase.CLOSED,null,null);lock.notifyAll();}worker.interrupt();}
}
