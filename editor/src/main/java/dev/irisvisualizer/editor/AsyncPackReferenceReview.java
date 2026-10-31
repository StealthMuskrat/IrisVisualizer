package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned explicit requests; one daemon scan and one replaceable pending immutable source. */
public final class AsyncPackReferenceReview implements AutoCloseable {
    public enum Phase { IDLE, SCANNING, READY, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,PackReferenceAudit.Audit audit,String error) {}
    @FunctionalInterface interface Scanner { PackReferenceAudit.Audit inspect(PackResourceView source,BooleanSupplier cancelled); }
    private record Job(long request,ReferenceScanSource source,AtomicBoolean cancelled) {}
    private record Outcome(long request,PackReferenceAudit.Audit audit,String error) {}
    private final Object lock=new Object();
    private final Scanner scanner;
    private final Thread worker;
    private PackWorkspace workspace;
    private long revision=-1,generation;
    private boolean closed;
    private Job active,pending;
    private Outcome completed;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null);
    private PackReferenceAudit.Query previous;
    private PackReferenceAudit.Page page;

    public AsyncPackReferenceReview() { this(PackReferenceAudit::inspect,runnable -> new Thread(runnable,"iris-resource-link-review")); }
    AsyncPackReferenceReview(Scanner scanner,ThreadFactory threads) {
        this.scanner=Objects.requireNonNull(scanner);worker=Objects.requireNonNull(threads).newThread(this::run);
        if (worker==null) throw new IllegalArgumentException("Reference worker factory returned no thread");
        worker.setDaemon(true);worker.start();
    }
    private void invalidate() {
        generation++;if (active!=null) active.cancelled().set(true);if (pending!=null) pending.cancelled().set(true);
        pending=null;completed=null;previous=null;page=null;
    }
    /** Call on the UI thread. Capture copies at most 8 MB of JSON; parsing happens only on the worker. */
    public Snapshot refresh(PackWorkspace current) {
        Objects.requireNonNull(current);
        long request;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Reference review is closed");
            invalidate();workspace=current;revision=current.revision();request=generation;snapshot=new Snapshot(generation,Phase.SCANNING,null,null);
        }
        ReferenceScanSource source;
        try { source=ReferenceScanSource.capture(current); }
        catch (Exception failure) {
            synchronized (lock) { if (!closed && request==generation) snapshot=new Snapshot(generation,Phase.FAILED,null,error(failure));return snapshot; }
        }
        synchronized (lock) { if (!closed && request==generation) { pending=new Job(request,source,new AtomicBoolean());lock.notifyAll(); }return snapshot; }
    }
    /** Edits invalidate without scheduling another scan. Save and filter/navigation changes retain results. */
    public Snapshot poll(PackWorkspace current) {
        Objects.requireNonNull(current);
        synchronized (lock) {
            if (closed) return snapshot;
            if (workspace!=current || revision!=current.revision()) {
                invalidate();workspace=current;revision=current.revision();snapshot=new Snapshot(generation,Phase.IDLE,null,null);
            }
            if (completed!=null && completed.request()==generation && snapshot.phase()==Phase.SCANNING) {
                snapshot=new Snapshot(generation,completed.error()==null ? Phase.READY : Phase.FAILED,completed.audit(),completed.error());completed=null;
            }
            return snapshot;
        }
    }
    /** Paging recorded diagnostics never schedules work or rereads source payloads. */
    public PackReferenceAudit.Result query(PackWorkspace current,PackReferenceAudit.Query query) { var selected=page(current,query,0);return selected==null ? null : selected.result(); }
    public PackReferenceAudit.Page page(PackWorkspace current,PackReferenceAudit.Query query,int requested) {
        Objects.requireNonNull(query);var state=poll(current);if (state.phase()!=Phase.READY) return null;
        if (page==null || !query.equals(previous) || page.window().page()!=ReviewPagination.Window.at(page.result().matches(),requested).page()) { page=PackReferenceAudit.page(state.audit(),query,requested);previous=query; }
        return page;
    }
    public void clear() {
        synchronized (lock) {
            if (closed) return;invalidate();workspace=null;revision=-1;snapshot=new Snapshot(generation,Phase.IDLE,null,null);
        }
    }
    private static String error(Exception failure) {
        String detail=failure.getMessage();if (detail==null || detail.isBlank()) detail="Resource link scan failed ("+failure.getClass().getSimpleName()+")";
        return InspectorText.label(detail,512);
    }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (!closed && pending==null) {
                    try { lock.wait(); } catch (InterruptedException interrupted) { if (closed) return; }
                }
                if (closed) return;active=job=pending;pending=null;
            }
            Outcome outcome=null;
            try { outcome=new Outcome(job.request(),Objects.requireNonNull(scanner.inspect(job.source(),job.cancelled()::get),"Reference scanner returned no audit"),null); }
            catch (CancellationException cancelled) { outcome=new Outcome(job.request(),null,"Resource link scan cancelled; refresh to retry"); }
            catch (Exception failure) { outcome=new Outcome(job.request(),null,error(failure)); }
            synchronized (lock) {
                active=null;
                if (!closed && !job.cancelled().get() && job.request()==generation) completed=outcome;
            }
        }
    }
    /** Never join a scanner on the UI thread; an interruption-ignoring scan cannot publish after close. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;closed=true;invalidate();workspace=null;revision=-1;snapshot=new Snapshot(generation,Phase.CLOSED,null,null);lock.notifyAll();
        }
        worker.interrupt();
    }
}
