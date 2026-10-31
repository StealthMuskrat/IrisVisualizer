package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned advisory review. One active detached source, no queue, no automatic disk refresh. */
public final class AsyncPendingDiskReview implements AutoCloseable {
    public enum Phase { IDLE, CHECKING, READY, CANCELLED, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,Path directory,int total,int checked,long bytes,PendingDiskReview.Report report,String detail) {}
    @FunctionalInterface interface Scanner { PendingDiskReview.Report inspect(PendingDiskReview.Source source,BooleanSupplier cancelled,PendingDiskReview.Progress progress); }
    private static final class Job {
        final long request;final PendingDiskReview.Source source;final AtomicBoolean cancelled=new AtomicBoolean();volatile int checked;volatile long bytes;
        Job(long request,PendingDiskReview.Source source) { this.request=request;this.source=source; }
    }
    private final Object lock=new Object();private final Scanner scanner;private final Thread worker;
    private Job pending,active;private long generation,revision=-1,saveRevision=-1;private PackWorkspace workspace;private Path directory;private boolean closed;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,0,0,0,null,null);
    private WorkspaceChanges.Query previous;private boolean previousAttention;private PendingDiskReview.Page page;
    public AsyncPendingDiskReview() { this(PendingDiskReview::inspect,runnable->new Thread(runnable,"iris-pending-disk-review")); }
    AsyncPendingDiskReview(Scanner scanner,ThreadFactory threads) {
        this.scanner=Objects.requireNonNull(scanner);worker=Objects.requireNonNull(threads).newThread(this::run);if (worker==null) throw new IllegalArgumentException("Disk review thread factory returned no thread");worker.setDaemon(true);worker.start();
    }
    public boolean busy() { synchronized (lock) { return active!=null || pending!=null; } }
    /** Null selection captures all pending destinations. Invalid/busy requests retain the current receipt. */
    public Snapshot start(PackWorkspace current,WorkspaceChanges.Entry selected) throws IOException {
        Objects.requireNonNull(current);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Pending disk review is closed");if (active!=null || pending!=null) throw new IOException("A disk review is active; wait for it to finish or cancel before starting another");
            var source=current.capturePendingDiskReview(selected);workspace=current;revision=current.revision();saveRevision=current.saveRevision();directory=current.directory();previous=null;page=null;
            pending=new Job(++generation,source);snapshot=new Snapshot(generation,Phase.CHECKING,directory,source.entries(),0,0,null,null);lock.notifyAll();return snapshot;
        }
    }
    private void cancelJobs() { if (active!=null) active.cancelled.set(true);if (pending!=null) pending.cancelled.set(true);pending=null;generation++;previous=null;page=null; }
    /** Edits, Undo, saves (including partial saves) and workspace switches invalidate without rescanning. */
    public Snapshot poll(PackWorkspace current) {
        Objects.requireNonNull(current);
        synchronized (lock) {
            if (closed) return snapshot;
            if (workspace!=current || revision!=current.revision() || saveRevision!=current.saveRevision() || !Objects.equals(directory,current.directory())) {
                boolean hadReview=snapshot.phase()!=Phase.IDLE;cancelJobs();workspace=current;revision=current.revision();saveRevision=current.saveRevision();directory=current.directory();snapshot=new Snapshot(generation,Phase.IDLE,directory,0,0,0,null,hadReview ? "Workspace or save baseline changed; review again explicitly." : null);
            }
            var job=active!=null ? active : pending;if (snapshot.phase()==Phase.CHECKING && job!=null) return new Snapshot(job.request,Phase.CHECKING,directory,job.source.entries(),job.checked,job.bytes,null,null);
            return snapshot;
        }
    }
    /** Compatibility entry point: first bounded result page. Paging never starts a disk scan. */
    public PendingDiskReview.Page query(PackWorkspace current,WorkspaceChanges.Query query,boolean attentionOnly) { return query(current,query,attentionOnly,0); }
    public PendingDiskReview.Page query(PackWorkspace current,WorkspaceChanges.Query query,boolean attentionOnly,int requested) {
        Objects.requireNonNull(query);var state=poll(current);if (state.phase()!=Phase.READY) return null;
        if (page==null || !query.equals(previous) || previousAttention!=attentionOnly || page.window().page()!=ReviewPagination.Window.at(page.matches(),requested).page()) { page=PendingDiskReview.filter(state.report(),query,attentionOnly,requested);previous=query;previousAttention=attentionOnly; }return page;
    }
    public void cancel() { synchronized (lock) { if (closed || snapshot.phase()!=Phase.CHECKING) return;var old=snapshot;cancelJobs();snapshot=new Snapshot(generation,Phase.CANCELLED,old.directory(),old.total(),0,0,null,"Disk review cancelled. Review again explicitly to retry."); }worker.interrupt(); }
    public void clear() { synchronized (lock) { if (closed) return;cancelJobs();workspace=null;revision=-1;saveRevision=-1;directory=null;snapshot=new Snapshot(generation,Phase.IDLE,null,0,0,0,null,null); }worker.interrupt(); }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                Thread.interrupted();while (!closed && pending==null) try { lock.wait(); } catch (InterruptedException interrupted) { if (closed) return; }
                if (closed) return;active=job=pending;pending=null;
            }
            PendingDiskReview.Report report=null;String detail=null;Phase phase;
            try { report=Objects.requireNonNull(scanner.inspect(job.source,job.cancelled::get,(checked,bytes)->{ job.checked=checked;job.bytes=bytes; }),"Disk scanner returned no report");phase=Phase.READY; }
            catch (CancellationException cancelled) { phase=Phase.CANCELLED;detail="Disk review cancelled; review again explicitly."; }
            catch (Exception failure) { phase=Phase.FAILED;detail=InspectorText.label(Objects.toString(failure.getMessage(),"Disk review failed ("+failure.getClass().getSimpleName()+")"),512); }
            synchronized (lock) {
                active=null;if (!closed && !job.cancelled.get() && job.request==generation) snapshot=new Snapshot(job.request,phase,job.source.directory(),job.source.entries(),job.checked,job.bytes,phase==Phase.READY ? report : null,detail);
            }
        }
    }
    @Override public void close() { synchronized (lock) { if (closed) return;closed=true;cancelJobs();workspace=null;directory=null;snapshot=new Snapshot(generation,Phase.CLOSED,null,0,0,0,null,null);lock.notifyAll(); }worker.interrupt(); }
}
