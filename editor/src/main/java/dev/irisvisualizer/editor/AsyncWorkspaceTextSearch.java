package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned active query; one daemon search and one replaceable latest pending immutable source. */
public final class AsyncWorkspaceTextSearch implements AutoCloseable {
    public enum Phase { IDLE, SEARCHING, READY, FAILED, CLOSED }
    public record Snapshot(long request,WorkspaceTextSearch.Query query,Phase phase,WorkspaceTextSearch.Result result,String error) {}
    @FunctionalInterface interface Searcher { WorkspaceTextSearch.Result search(PackResourceView source,WorkspaceTextSearch.Query query,BooleanSupplier cancelled); }
    private record Job(long request,WorkspaceTextSearch.Query query,TextSearchSource source,AtomicBoolean cancelled) {}
    private record Outcome(long request,WorkspaceTextSearch.Result result,String error) {}
    private final Object lock=new Object();
    private final Searcher searcher;
    private final Thread worker;
    private PackWorkspace workspace;
    private WorkspaceTextSearch.Query query;
    private long revision=-1,saveRevision=-1,generation;
    private boolean closed;
    private Job active,pending;
    private Outcome completed;
    private Snapshot snapshot=new Snapshot(0,null,Phase.IDLE,null,null);
    public AsyncWorkspaceTextSearch() { this((source,query,cancelled) -> WorkspaceTextSearch.search(source,query,path -> true,cancelled),runnable -> new Thread(runnable,"iris-json-text-search")); }
    AsyncWorkspaceTextSearch(Searcher searcher,ThreadFactory threads) {
        this.searcher=Objects.requireNonNull(searcher);worker=Objects.requireNonNull(threads).newThread(this::run);
        if (worker==null) throw new IllegalArgumentException("Text search worker factory returned no thread");worker.setDaemon(true);worker.start();
    }
    private void invalidate() {
        generation++;if (active!=null) active.cancelled().set(true);if (pending!=null) pending.cancelled().set(true);pending=null;completed=null;
    }
    public Snapshot select(PackWorkspace current,WorkspaceTextSearch.Query selected) { return select(current,selected,false); }
    public Snapshot retry(PackWorkspace current,WorkspaceTextSearch.Query selected) { return select(current,selected,true); }
    /** Capture is bounded UI-thread copying; decoding/matching runs only on the worker. */
    private Snapshot select(PackWorkspace current,WorkspaceTextSearch.Query selected,boolean force) {
        Objects.requireNonNull(current);Objects.requireNonNull(selected);long request;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Text search is closed");
            if (!force && workspace==current && selected.equals(query) && revision==current.revision() && (!selected.changedOnly() || saveRevision==current.saveRevision())) return pollLocked();
            invalidate();workspace=current;query=selected;revision=current.revision();saveRevision=current.saveRevision();request=generation;snapshot=new Snapshot(request,selected,Phase.SEARCHING,null,null);
        }
        TextSearchSource source;
        try { source=TextSearchSource.capture(current,selected); }
        catch (Exception failure) {
            synchronized (lock) { if (!closed && request==generation) snapshot=new Snapshot(request,selected,Phase.FAILED,null,error(failure));return snapshot; }
        }
        synchronized (lock) { if (!closed && request==generation) { pending=new Job(request,selected,source,new AtomicBoolean());lock.notifyAll(); }return snapshot; }
    }
    public Snapshot poll() { synchronized (lock) { return pollLocked(); } }
    private Snapshot pollLocked() {
        if (completed!=null && completed.request()==generation && snapshot.phase()==Phase.SEARCHING) {
            snapshot=new Snapshot(generation,query,completed.error()==null ? Phase.READY : Phase.FAILED,completed.result(),completed.error());completed=null;
        }
        return snapshot;
    }
    public void clear() {
        synchronized (lock) { if (closed) return;invalidate();workspace=null;query=null;revision=-1;saveRevision=-1;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null); }
    }
    private static String error(Exception failure) {
        String detail=failure.getMessage();if (detail==null || detail.isBlank()) detail="Text search failed ("+failure.getClass().getSimpleName()+")";return InspectorText.label(detail,512);
    }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (!closed && pending==null) try { lock.wait(); } catch (InterruptedException interrupted) { if (closed) return; }
                if (closed) return;active=job=pending;pending=null;
            }
            Outcome outcome;
            try { outcome=new Outcome(job.request(),Objects.requireNonNull(searcher.search(job.source(),job.query(),job.cancelled()::get),"Text searcher returned no result"),null); }
            catch (CancellationException cancelled) { outcome=new Outcome(job.request(),null,"Text search cancelled; search again to retry"); }
            catch (Exception failure) { outcome=new Outcome(job.request(),null,error(failure)); }
            synchronized (lock) { active=null;if (!closed && !job.cancelled().get() && job.request()==generation) completed=outcome; }
        }
    }
    @Override public void close() {
        synchronized (lock) { if (closed) return;closed=true;invalidate();workspace=null;query=null;revision=-1;saveRevision=-1;snapshot=new Snapshot(generation,null,Phase.CLOSED,null,null);lock.notifyAll(); }
        worker.interrupt();
    }
}
