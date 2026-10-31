package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned active query; one daemon search and one replaceable latest pending immutable source. */
public final class AsyncWorkspaceTextSearch implements AutoCloseable {
    public enum Phase { IDLE, SEARCHING, READY, FAILED, CLOSED }
    public record Snapshot(long request,WorkspaceTextSearch.Query query,Phase phase,WorkspaceTextSearch.Result result,String error,ReviewPagination.Window window) {
        public Snapshot(long request,WorkspaceTextSearch.Query query,Phase phase,WorkspaceTextSearch.Result result,String error) { this(request,query,phase,result,error,result==null ? null : ReviewPagination.Window.at(result.matches(),0)); }
    }
    @FunctionalInterface interface Searcher { WorkspaceTextSearch.Result search(PackResourceView source,WorkspaceTextSearch.Query query,BooleanSupplier cancelled); }
    @FunctionalInterface interface PagedSearcher { WorkspaceTextSearch.Page search(PackResourceView source,WorkspaceTextSearch.Query query,int page,BooleanSupplier cancelled); }
    private record Job(long request,WorkspaceTextSearch.Query query,TextSearchSource source,int page,AtomicBoolean cancelled) {}
    private record Outcome(long request,WorkspaceTextSearch.Page page,String error) {}
    private final Object lock=new Object();
    private final PagedSearcher searcher;
    private TextSearchSource captured;
    private final Thread worker;
    private PackWorkspace workspace;
    private WorkspaceTextSearch.Query query;
    private long revision=-1,saveRevision=-1,generation;
    private boolean closed;
    private Job active,pending;
    private Outcome completed;
    private Snapshot snapshot=new Snapshot(0,null,Phase.IDLE,null,null);
    public AsyncWorkspaceTextSearch() { this((source,query,page,cancelled) -> WorkspaceTextSearch.searchPage(source,query,path -> true,page,cancelled),runnable -> new Thread(runnable,"iris-json-text-search")); }
    /** Compatibility test/caller adapter for the original first-page search callback. */
    AsyncWorkspaceTextSearch(Searcher searcher,ThreadFactory threads) {
        this(adapt(searcher),threads);
    }
    private static PagedSearcher adapt(Searcher searcher) {
        Objects.requireNonNull(searcher);return (source,query,page,cancelled)->{
            if(page!=0)throw new IllegalArgumentException("This search callback supports only the first page");
            var result=Objects.requireNonNull(searcher.search(source,query,cancelled),"Text searcher returned no result");
            return new WorkspaceTextSearch.Page(ReviewPagination.Window.at(result.matches(),0),result);
        };
    }
    AsyncWorkspaceTextSearch(PagedSearcher searcher,ThreadFactory threads) {
        this.searcher=Objects.requireNonNull(searcher);worker=Objects.requireNonNull(threads).newThread(this::run);
        if (worker==null) throw new IllegalArgumentException("Text search worker factory returned no thread");worker.setDaemon(true);worker.start();
    }
    private void invalidate() {
        generation++;if (active!=null) active.cancelled().set(true);if (pending!=null) pending.cancelled().set(true);pending=null;completed=null;captured=null;
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
        synchronized (lock) { if (!closed && request==generation) { captured=source;pending=new Job(request,selected,source,0,new AtomicBoolean());lock.notifyAll(); }return snapshot; }
    }
    /** Page changes reuse the private capture; only matching/decoding runs again, on the same daemon worker. */
    public Snapshot page(PackWorkspace current,WorkspaceTextSearch.Query selected,int requested) {
        select(current,selected,false);
        synchronized(lock) {
            var state=pollLocked();
            if(captured==null || state.window()==null || (state.phase()!=Phase.READY && state.phase()!=Phase.SEARCHING))return state;
            var window=ReviewPagination.Window.at(state.window().matches(),requested);
            if(window.page()==state.window().page())return state;
            var source=captured;invalidate();captured=source;
            pending=new Job(generation,query,source,window.page(),new AtomicBoolean());
            snapshot=new Snapshot(generation,query,Phase.SEARCHING,null,null,window);lock.notifyAll();return snapshot;
        }
    }
    /** Frame-end invalidation also releases hidden/collapsed stale captures, without scheduling a new search. */
    public Snapshot poll(PackWorkspace current) {
        Objects.requireNonNull(current);
        synchronized(lock) {
            if(!closed && workspace!=null && (workspace!=current || revision!=current.revision() || query.changedOnly() && saveRevision!=current.saveRevision())) {
                invalidate();workspace=null;query=null;revision=-1;saveRevision=-1;snapshot=new Snapshot(generation,null,Phase.IDLE,null,null);
            }
            return pollLocked();
        }
    }
    public Snapshot poll() { synchronized (lock) { return pollLocked(); } }
    private Snapshot pollLocked() {
        if (completed!=null && completed.request()==generation && snapshot.phase()==Phase.SEARCHING) {
            var result=completed.page();snapshot=new Snapshot(generation,query,completed.error()==null ? Phase.READY : Phase.FAILED,result==null ? null : result.result(),completed.error(),result==null ? null : result.window());completed=null;
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
            try { outcome=new Outcome(job.request(),Objects.requireNonNull(searcher.search(job.source(),job.query(),job.page(),job.cancelled()::get),"Text searcher returned no result"),null); }
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
