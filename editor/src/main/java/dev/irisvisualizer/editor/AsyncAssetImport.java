package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned target validation; background jobs receive paths/budgets only and never access the workspace. */
public final class AsyncAssetImport implements AutoCloseable {
    public enum Phase { IDLE, CAPTURING, READY, STALE, FAILED, APPLIED, CLOSED }
    public record Snapshot(long request,Phase phase,StagedAssetImport.Target target,Path source,AssetReplacement.Receipt receipt,String detail) {}
    @FunctionalInterface interface Reader { AssetReplacement.Prepared read(Path source,String target,long budget,BooleanSupplier cancelled) throws IOException; }
    private record Job(long request,Path source,String target,long budget,AtomicBoolean cancelled) {}
    private record Outcome(long request,AssetReplacement.Prepared prepared,String error) {}
    private final Object lock=new Object();
    private final Reader reader;
    private final Thread worker;
    private PackWorkspace workspace;
    private StagedAssetImport.Target target;
    private Path source;
    private long checkedResourceRevision;
    private long generation;
    private boolean closed;
    private Job active,pending;
    private Outcome completed;
    private AssetReplacement.Prepared prepared;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null,null,null);
    public AsyncAssetImport() { this(AssetReplacement::prepare,runnable -> new Thread(runnable,"iris-asset-import")); }
    AsyncAssetImport(Reader reader,ThreadFactory threads) {
        this.reader=Objects.requireNonNull(reader);worker=Objects.requireNonNull(threads).newThread(this::run);
        if (worker==null) throw new IllegalArgumentException("Asset capture worker factory returned no thread");worker.setDaemon(true);worker.start();
    }
    private void invalidate() {
        generation++;if (active!=null) active.cancelled().set(true);if (pending!=null) pending.cancelled().set(true);pending=null;completed=null;prepared=null;
    }
    public Snapshot request(PackWorkspace current,Path source,String key) throws IOException {
        var next=StagedAssetImport.target(current,source,key);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Asset import is closed");
            invalidate();workspace=current;target=next;this.source=next.source();checkedResourceRevision=current.resourceRevision();snapshot=new Snapshot(generation,Phase.CAPTURING,target,this.source,null,null);
            pending=new Job(generation,this.source,next.path(),next.budget(),new AtomicBoolean());lock.notifyAll();return snapshot;
        }
    }
    public Snapshot poll(PackWorkspace current) {
        Objects.requireNonNull(current);
        synchronized (lock) {
            if (closed) return snapshot;
            if (workspace!=current) { invalidate();workspace=current;target=null;source=null;snapshot=new Snapshot(generation,Phase.IDLE,null,null,null,null); }
            if (target!=null && snapshot.phase()!=Phase.STALE && snapshot.phase()!=Phase.APPLIED && checkedResourceRevision!=current.resourceRevision()) {
                checkedResourceRevision=current.resourceRevision();
                if (!StagedAssetImport.available(current,target)) { invalidate();snapshot=new Snapshot(generation,Phase.STALE,target,source,null,"Import destination is no longer available; choose a new destination and capture again"); }
            }
            if (completed!=null && completed.request()==generation && snapshot.phase()==Phase.CAPTURING) {
                prepared=completed.prepared();snapshot=new Snapshot(generation,completed.error()==null ? Phase.READY : Phase.FAILED,target,source,prepared==null ? null : prepared.receipt(),completed.error());completed=null;
            }
            return snapshot;
        }
    }
    /** Fresh destination/capacity checks; captured bytes only, with no source re-read or draft transition. */
    public String apply(PackWorkspace current,boolean acknowledgeHistoryClear) throws IOException {
        var state=poll(current);if (state.phase()!=Phase.READY) throw new IOException("No current captured import is ready; capture again");
        String path=StagedAssetImport.apply(current,target,prepared,acknowledgeHistoryClear);
        synchronized (lock) { var receipt=prepared.receipt();invalidate();snapshot=new Snapshot(generation,Phase.APPLIED,target,source,receipt,"Captured asset imported in memory. Applied-edit history was cleared. Save remains explicit.");return path; }
    }
    public void clear() { synchronized (lock) { if (closed) return;invalidate();workspace=null;target=null;source=null;snapshot=new Snapshot(generation,Phase.IDLE,null,null,null,null); } }
    private static String error(Exception failure) { String message=failure.getMessage();return InspectorText.label(message==null || message.isBlank() ? "Asset capture failed ("+failure.getClass().getSimpleName()+")" : message,512); }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (!closed && pending==null) { try { lock.wait(); } catch (InterruptedException interrupted) { if (closed) return; } }
                if (closed) return;active=job=pending;pending=null;
            }
            Outcome outcome;
            try { outcome=new Outcome(job.request(),Objects.requireNonNull(reader.read(job.source(),job.target(),job.budget(),job.cancelled()::get),"Asset reader returned no captured bytes"),null); }
            catch (CancellationException cancelled) { outcome=new Outcome(job.request(),null,"Asset capture cancelled; choose a source again to retry"); }
            catch (Exception failure) { outcome=new Outcome(job.request(),null,error(failure)); }
            synchronized (lock) { active=null;if (!closed && !job.cancelled().get() && job.request()==generation) completed=outcome; }
        }
    }
    @Override public void close() { synchronized (lock) { if (closed) return;closed=true;invalidate();workspace=null;target=null;source=null;snapshot=new Snapshot(generation,Phase.CLOSED,null,null,null,null);lock.notifyAll(); }worker.interrupt(); }
}
