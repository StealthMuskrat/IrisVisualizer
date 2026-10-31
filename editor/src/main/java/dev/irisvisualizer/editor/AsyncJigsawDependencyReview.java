package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned pinned-root requests; one daemon scan and one replaceable pending immutable source. */
public final class AsyncJigsawDependencyReview implements AutoCloseable {
    public enum Phase { IDLE, SCANNING, READY, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,JigsawDependencyReview.Review review,String error) {}
    @FunctionalInterface interface Scanner { JigsawDependencyReview.Review inspect(PackResourceView source,String root,BooleanSupplier cancelled); }
    @FunctionalInterface interface Capture { JigsawScanSource capture(PackWorkspace workspace,String root); }
    private record Job(long request,JigsawScanSource source,String root,AtomicBoolean cancelled) {}
    private record Outcome(long request,JigsawDependencyReview.Review review,String error) {}
    private final Object lock=new Object();
    private final Scanner scanner;
    private final Capture capture;
    private final Thread worker;
    private PackWorkspace workspace;
    private long revision=-1,generation;
    private String root;
    private boolean closed;
    private Job active,pending;
    private Outcome completed;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null);
    private JigsawDependencyReview.Query previous;
    private JigsawDependencyReview.Page page;

    public AsyncJigsawDependencyReview() { this(JigsawDependencyReview::inspect,runnable -> new Thread(runnable,"iris-jigsaw-dependency-review")); }
    AsyncJigsawDependencyReview(Scanner scanner,ThreadFactory threads) {
        this(scanner,threads,PackWorkspace::captureJigsawReview);
    }
    AsyncJigsawDependencyReview(Scanner scanner,ThreadFactory threads,Capture capture) {
        this.scanner=Objects.requireNonNull(scanner);this.capture=Objects.requireNonNull(capture);worker=Objects.requireNonNull(threads).newThread(this::run);
        if (worker==null) throw new IllegalArgumentException("Jigsaw worker factory returned no thread");
        worker.setDaemon(true);worker.start();
    }
    private void invalidate() {
        generation++;if (active!=null) active.cancelled().set(true);if (pending!=null) pending.cancelled().set(true);
        pending=null;completed=null;previous=null;page=null;
    }
    /** Call on the UI thread. Capture pins at most 16 MB of owned JSON; copying/parsing happen only on the worker. */
    public Snapshot refresh(PackWorkspace current,String selectedRoot) {
        Objects.requireNonNull(current);
        long request;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Jigsaw review is closed");
            invalidate();workspace=current;revision=current.revision();root=selectedRoot;request=generation;snapshot=new Snapshot(generation,Phase.SCANNING,null,null);
        }
        JigsawScanSource source;
        try { source=Objects.requireNonNull(capture.capture(current,selectedRoot),"Jigsaw dependency review capture returned no source"); }
        catch (OutOfMemoryError exhausted) { return captureFailed(request,"Not enough memory to capture jigsaw dependency review metadata; clear the review and refresh explicitly."); }
        catch (Exception failure) { return captureFailed(request,error(failure)); }
        synchronized (lock) { if (!closed && request==generation) { pending=new Job(request,source,selectedRoot,new AtomicBoolean());lock.notifyAll(); }return snapshot; }
    }
    private Snapshot captureFailed(long request,String detail) {
        synchronized(lock){if(!closed && request==generation)snapshot=new Snapshot(generation,Phase.FAILED,null,detail);return snapshot;}
    }
    /** Edits invalidate without scheduling another scan. Save and filter/navigation changes retain results. */
    public Snapshot poll(PackWorkspace current,String selectedRoot) {
        Objects.requireNonNull(current);
        synchronized (lock) {
            if (closed) return snapshot;
            if (workspace!=current || revision!=current.revision() || !Objects.equals(root,selectedRoot)) {
                invalidate();workspace=current;revision=current.revision();root=selectedRoot;snapshot=new Snapshot(generation,Phase.IDLE,null,null);
            }
            if (completed!=null && completed.request()==generation && snapshot.phase()==Phase.SCANNING) {
                snapshot=new Snapshot(generation,completed.error()==null ? Phase.READY : Phase.FAILED,completed.review(),completed.error());completed=null;
            }
            return snapshot;
        }
    }
    public JigsawDependencyReview.Result query(PackWorkspace current,String selectedRoot,JigsawDependencyReview.Query query) { var selected=page(current,selectedRoot,query,0);return selected==null ? null : selected.result(); }
    public JigsawDependencyReview.Page page(PackWorkspace current,String selectedRoot,JigsawDependencyReview.Query query,int requested) {
        Objects.requireNonNull(query);var state=poll(current,selectedRoot);if (state.phase()!=Phase.READY) return null;
        if (page==null || !query.equals(previous) || page.window().page()!=ReviewPagination.Window.at(page.result().matches(),requested).page()) { page=JigsawDependencyReview.page(state.review(),query,requested);previous=query; }
        return page;
    }
    public void clear() {
        synchronized (lock) {
            if (closed) return;invalidate();workspace=null;revision=-1;root=null;snapshot=new Snapshot(generation,Phase.IDLE,null,null);
        }
    }
    private static String error(Exception failure) {
        String detail=failure.getMessage();if (detail==null || detail.isBlank()) detail="Jigsaw dependency scan failed ("+failure.getClass().getSimpleName()+")";
        return InspectorText.label(detail,512);
    }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                Thread.interrupted();
                while (!closed && pending==null) {
                    try { lock.wait(); } catch (InterruptedException interrupted) { if (closed) return; }
                }
                if (closed) return;active=job=pending;pending=null;
            }
            Outcome outcome=null;
            try {
                var review=Objects.requireNonNull(scanner.inspect(job.source().view(job.cancelled()::get),job.root(),job.cancelled()::get),"Jigsaw scanner returned no review");
                if(!Objects.equals(job.root(),review.root()))throw new IllegalArgumentException("Jigsaw scanner returned a different root");
                outcome=new Outcome(job.request(),review,null);
            }
            catch (CancellationException cancelled) { outcome=new Outcome(job.request(),null,"Jigsaw dependency scan cancelled; refresh to retry"); }
            catch (OutOfMemoryError exhausted) { outcome=new Outcome(job.request(),null,"Not enough memory to inspect jigsaw dependency review; clear the review and refresh explicitly."); }
            catch (Exception failure) { outcome=new Outcome(job.request(),null,error(failure)); }
            synchronized (lock) {
                active=null;
                if (!closed && !job.cancelled().get() && job.request()==generation) completed=outcome;
            }
            job=null;outcome=null;
        }
    }
    /** Never join a scanner on the UI thread; an interruption-ignoring scan cannot publish after close. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;closed=true;invalidate();workspace=null;revision=-1;root=null;snapshot=new Snapshot(generation,Phase.CLOSED,null,null);lock.notifyAll();
        }
        worker.interrupt();
    }
}
