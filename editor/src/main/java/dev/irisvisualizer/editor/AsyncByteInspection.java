package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Explicit UI-owned byte inspection. One worker and one latest pending page over an immutable source reference. */
public final class AsyncByteInspection implements AutoCloseable {
    public enum Phase { IDLE, READING, READY, FAILED, CLOSED }
    public record Snapshot(long request,String path,Phase phase,ByteInspection.Window window,long hashed,ByteInspection.Result result,String error) {}
    @FunctionalInterface interface Reader {ByteInspection.Result read(ByteInspection.Source source,int page,BooleanSupplier cancelled,LongConsumer progress) throws Exception;}
    private static final class Job {
        final long request;final ByteInspection.Source source;final ByteInspection.Window window;final AtomicBoolean cancelled=new AtomicBoolean();volatile long hashed;
        Job(long request,ByteInspection.Source source,int page){this.request=request;this.source=source;window=ByteInspection.Window.at(source.size(),page);}
        void progress(long bytes){hashed=bytes;}
    }
    private final Object lock=new Object();private final Reader reader;private final Thread worker;
    private PackWorkspace owner;private ByteInspection.Source source;private String path;private long stamp,generation;private boolean closed;
    private Job active,pending;
    private Snapshot snapshot=new Snapshot(0,null,Phase.IDLE,null,0,null,null);
    public AsyncByteInspection(){this(ByteInspection::inspect,r->new Thread(r,"iris-byte-inspection"));}
    AsyncByteInspection(Reader reader,ThreadFactory threads){this.reader=Objects.requireNonNull(reader);worker=Objects.requireNonNull(threads).newThread(this::run);if(worker==null)throw new IllegalArgumentException("Byte inspection thread factory returned no thread");worker.setDaemon(true);worker.start();}
    private void invalidate(){generation++;if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;}
    /** Explicit fresh inspection. Missing resources fail before replacing valid accepted work. */
    public Snapshot start(PackWorkspace current,String selected) throws IOException {
        Objects.requireNonNull(current);var captured=current.captureByteInspection(selected);long revision=current.contentRevision(selected);
        synchronized(lock){if(closed)throw new IllegalStateException("Byte inspection is closed");invalidate();owner=current;source=captured;path=selected;stamp=revision;return enqueue(0);}
    }
    /** Page changes reuse the same immutable applied bytes and worker-memoized digest. */
    public Snapshot page(PackWorkspace current,String selected,int requested) throws IOException {
        poll(current,selected);synchronized(lock){if(closed)throw new IllegalStateException("Byte inspection is closed");if(source==null)throw new IOException("Inspect the selected resource first");var window=ByteInspection.Window.at(source.size(),requested);invalidate();return enqueue(window.page());}
    }
    private Snapshot enqueue(int page){pending=new Job(generation,source,page);snapshot=new Snapshot(generation,path,Phase.READING,pending.window,0,null,null);lock.notifyAll();return snapshot;}
    public Snapshot poll(PackWorkspace current,String selected){
        Objects.requireNonNull(current);synchronized(lock){if(closed)return snapshot;
            if(owner!=null && (owner!=current || !Objects.equals(path,selected) || !current.paths().contains(path) || current.contentRevision(path)!=stamp)){invalidate();owner=null;source=null;path=null;snapshot=new Snapshot(generation,null,Phase.IDLE,null,0,null,null);}
            var job=active!=null && active.request==generation ? active : pending;
            if(snapshot.phase()==Phase.READING && job!=null)return new Snapshot(generation,path,Phase.READING,job.window,job.hashed,null,null);return snapshot;
        }
    }
    public void clear(){synchronized(lock){if(closed)return;invalidate();owner=null;source=null;path=null;snapshot=new Snapshot(generation,null,Phase.IDLE,null,0,null,null);}worker.interrupt();}
    private void run(){
        while(true){Job job;synchronized(lock){Thread.interrupted();while(!closed && pending==null)try{lock.wait();}catch(InterruptedException interrupted){if(closed)return;}if(closed)return;active=job=pending;pending=null;}
            ByteInspection.Result result=null;String error=null;
            try{result=Objects.requireNonNull(reader.read(job.source,job.window.page(),job.cancelled::get,job::progress),"Byte reader returned no result");if(!result.window().equals(job.window))throw new IOException("Byte reader returned another page");}
            catch(CancellationException cancelled){error="Byte inspection cancelled; inspect or request a page again explicitly.";}
            catch(OutOfMemoryError exhausted){error="Not enough memory to inspect these bytes; clear the inspection and retry explicitly.";}
            catch(Exception failure){String detail=failure.getMessage();if(detail==null || detail.isBlank())detail="Byte inspection failed ("+failure.getClass().getSimpleName()+")";error=InspectorText.label(detail,512);}
            synchronized(lock){active=null;if(!closed && !job.cancelled.get() && job.request==generation)snapshot=new Snapshot(generation,path,error==null ? Phase.READY : Phase.FAILED,job.window,job.hashed,error==null ? result : null,error);}
            job=null;result=null;
        }
    }
    @Override public void close(){synchronized(lock){if(closed)return;closed=true;invalidate();owner=null;source=null;path=null;snapshot=new Snapshot(generation,null,Phase.CLOSED,null,0,null,null);lock.notifyAll();}worker.interrupt();}
}
