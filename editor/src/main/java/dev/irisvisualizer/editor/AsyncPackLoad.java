package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One explicit load at a time, with a private complete candidate until a guarded UI handoff. */
public final class AsyncPackLoad implements AutoCloseable {
    public enum Phase { IDLE, LOADING, READY, OPENED, CANCELLED, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,PackLoads.Kind kind,Path source,PackLoads.Progress progress,PackLoads.Receipt receipt,String detail) {}
    @FunctionalInterface interface Reader { PackLoads.Prepared prepare(Path source,PackLoads.Kind kind,BooleanSupplier cancelled,PackLoads.ProgressSink progress) throws IOException; }
    private static final class Job {
        final long request;final Path source;final PackLoads.Kind kind;final AtomicBoolean cancelled=new AtomicBoolean();volatile PackLoads.Progress progress=new PackLoads.Progress(0,0,0,0,0);
        Job(long request,Path source,PackLoads.Kind kind) { this.request=request;this.source=source;this.kind=kind; }
    }
    private final Object lock=new Object();private final Reader reader;private final Thread worker;
    private long generation;private boolean closed;private Job active,pending;private PackWorkspace prepared;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null,null,null,null);
    public AsyncPackLoad() { this(PackLoads::prepare,runnable->new Thread(runnable,"iris-pack-load")); }
    AsyncPackLoad(Reader reader,ThreadFactory threads) {
        this.reader=Objects.requireNonNull(reader);worker=Objects.requireNonNull(threads).newThread(this::run);if (worker==null) throw new IllegalArgumentException("Pack loader thread factory returned no thread");worker.setDaemon(true);worker.start();
    }
    public boolean busy() { synchronized (lock) { return active!=null || pending!=null; } }
    public Snapshot start(Path source,PackLoads.Kind kind) throws IOException {
        Path path=Objects.requireNonNull(source).toAbsolutePath().normalize();Objects.requireNonNull(kind);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Pack loader is closed");if (active!=null || pending!=null) throw new IOException("A pack is loading; wait for completion or cancellation before loading another");
            prepared=null;pending=new Job(++generation,path,kind);snapshot=new Snapshot(generation,Phase.LOADING,kind,path,pending.progress,null,null);lock.notifyAll();return snapshot;
        }
    }
    public Snapshot poll() { synchronized (lock) { var job=active!=null ? active : pending;if (snapshot.phase()==Phase.LOADING && job!=null) return new Snapshot(job.request,Phase.LOADING,job.kind,job.source,job.progress,null,null);return snapshot; } }
    /** Call only inside the accepted UI workspace/draft transition, after all frame edits. No file reads occur here. */
    public PackWorkspace take(long request,PackWorkspace expectedOwner,PackWorkspace current) throws IOException {
        synchronized (lock) {
            if (expectedOwner==null || expectedOwner!=current) throw new IOException("The active workspace changed after this open request; open the loaded pack again explicitly");
            if (closed || snapshot.phase()!=Phase.READY || snapshot.request()!=request || prepared==null) throw new IOException("This loaded pack is no longer ready; review the current load result");
            PackWorkspace workspace=prepared;prepared=null;snapshot=new Snapshot(request,Phase.OPENED,snapshot.kind(),snapshot.source(),snapshot.progress(),snapshot.receipt(),"Loaded pack opened. Later source changes remain subject to ordinary Save conflict checks.");return workspace;
        }
    }
    private void cancelJobs() { if (active!=null) active.cancelled.set(true);if (pending!=null) pending.cancelled.set(true);pending=null;prepared=null;generation++; }
    public void cancel() {
        synchronized (lock) { if (closed || snapshot.phase()!=Phase.LOADING) return;var old=poll();cancelJobs();snapshot=new Snapshot(generation,Phase.CANCELLED,old.kind(),old.source(),old.progress(),null,"Pack load cancelled. The current workspace and drafts were retained."); }worker.interrupt();
    }
    public void clear() { synchronized (lock) { if (closed) return;cancelJobs();snapshot=new Snapshot(generation,Phase.IDLE,null,null,null,null,null); }worker.interrupt(); }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                Thread.interrupted();while (!closed && pending==null) try { lock.wait(); } catch(InterruptedException interrupted) { if(closed)return; }
                if(closed)return;active=job=pending;pending=null;
            }
            PackLoads.Prepared result=null;Phase phase;String detail=null;
            try { result=Objects.requireNonNull(reader.prepare(job.source,job.kind,job.cancelled::get,p->job.progress=p),"Pack loader returned no workspace");if(job.cancelled.get())throw new CancellationException();phase=Phase.READY; }
            catch(CancellationException cancelled) { phase=Phase.CANCELLED;detail="Pack loading cancelled; choose a source again to retry."; }
            catch(OutOfMemoryError exhausted) { phase=Phase.FAILED;detail="Not enough memory to load this pack alongside the current workspace. The current workspace was retained; choose a smaller pack or retry with more available memory."; }
            catch(Exception failure) { phase=Phase.FAILED;detail=InspectorText.label(Objects.toString(failure.getMessage(),"Pack load failed ("+failure.getClass().getSimpleName()+")"),512); }
            synchronized (lock) {
                active=null;if(!closed && !job.cancelled.get() && job.request==generation) {
                    prepared=phase==Phase.READY ? result.workspace : null;
                    snapshot=new Snapshot(job.request,phase,job.kind,job.source,job.progress,phase==Phase.READY ? result.receipt : null,detail);
                }
            }
            result=null; // Do not retain a cleared/opened candidate on the parked worker's stack.
        }
    }
    @Override public void close() { synchronized(lock) { if(closed)return;closed=true;cancelJobs();snapshot=new Snapshot(generation,Phase.CLOSED,null,null,null,null,null);lock.notifyAll(); }worker.interrupt(); }
}
