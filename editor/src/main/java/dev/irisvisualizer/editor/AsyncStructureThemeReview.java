package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned pinned-key requests; one daemon scan and one replaceable pending immutable source. */
public final class AsyncStructureThemeReview implements AutoCloseable {
    public enum Phase { IDLE, SCANNING, READY, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,String key,StructureThemeReferences.Review review,String error) {}
    @FunctionalInterface interface Scanner { StructureThemeReferences.Review inspect(PackResourceView source,String key,BooleanSupplier cancelled); }
    @FunctionalInterface interface Capture { ThemeReferenceSource capture(PackWorkspace workspace); }
    private record Job(long request,ThemeReferenceSource source,String key,AtomicBoolean cancelled) {}
    private record Outcome(long request,StructureThemeReferences.Review review,String error) {}
    private final Object lock=new Object();
    private final Scanner scanner;
    private final Capture capture;
    private final Thread worker;
    private PackWorkspace workspace;
    private long revision=-1,generation;
    private String key;
    private boolean closed;
    private Job active,pending;
    private Outcome completed;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null,null);

    public AsyncStructureThemeReview() { this(StructureThemeReferences::inspect,runnable -> new Thread(runnable,"iris-piece-theme-review")); }
    AsyncStructureThemeReview(Scanner scanner,ThreadFactory threads) {
        this(scanner,threads,PackWorkspace::captureThemeReferences);
    }
    AsyncStructureThemeReview(Scanner scanner,ThreadFactory threads,Capture capture) {
        this.scanner=Objects.requireNonNull(scanner);this.capture=Objects.requireNonNull(capture);worker=Objects.requireNonNull(threads).newThread(this::run);
        if (worker==null) throw new IllegalArgumentException("Piece-theme worker factory returned no thread");
        worker.setDaemon(true);worker.start();
    }
    private void invalidate() {
        generation++;if (active!=null) active.cancelled().set(true);if (pending!=null) pending.cancelled().set(true);
        pending=null;completed=null;
    }
    /** Call on the UI thread. Capture pins at most 8 MB of owned JSON; copying/parsing happen only on the worker. */
    public Snapshot refresh(PackWorkspace current,String selectedKey) {
        Objects.requireNonNull(current);StructureThemeReferences.validateKey(selectedKey);
        long request;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Piece-theme review is closed");
            invalidate();workspace=current;revision=current.revision();key=selectedKey;request=generation;snapshot=new Snapshot(generation,Phase.SCANNING,key,null,null);
        }
        ThemeReferenceSource source;
        try { source=Objects.requireNonNull(capture.capture(current),"Piece-theme capture returned no source"); }
        catch (OutOfMemoryError exhausted) { return captureFailed(request,"Not enough memory to capture piece-theme metadata; clear the review and refresh explicitly."); }
        catch (Exception failure) { return captureFailed(request,error(failure)); }
        synchronized (lock) { if (!closed && request==generation) { pending=new Job(request,source,selectedKey,new AtomicBoolean());lock.notifyAll(); }return snapshot; }
    }
    private Snapshot captureFailed(long request,String detail) {
        synchronized(lock){if(!closed && request==generation)snapshot=new Snapshot(generation,Phase.FAILED,key,null,detail);return snapshot;}
    }
    /** Edits invalidate without scheduling another scan. Save and filter/navigation changes retain results. */
    public Snapshot poll(PackWorkspace current) {
        Objects.requireNonNull(current);
        synchronized (lock) {
            if (closed) return snapshot;
            if (workspace!=current || revision!=current.revision()) {
                invalidate();if (workspace!=current) key=null;workspace=current;revision=current.revision();snapshot=new Snapshot(generation,Phase.IDLE,key,null,null);
            }
            if (completed!=null && completed.request()==generation && snapshot.phase()==Phase.SCANNING) {
                snapshot=new Snapshot(generation,completed.error()==null ? Phase.READY : Phase.FAILED,key,completed.review(),completed.error());completed=null;
            }
            return snapshot;
        }
    }
    public void clear() {
        synchronized (lock) {
            if (closed) return;invalidate();workspace=null;revision=-1;key=null;snapshot=new Snapshot(generation,Phase.IDLE,null,null,null);
        }
    }
    private static String error(Exception failure) {
        String detail=failure.getMessage();if (detail==null || detail.isBlank()) detail="Piece-theme scan failed ("+failure.getClass().getSimpleName()+")";
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
                var review=Objects.requireNonNull(scanner.inspect(job.source().view(job.cancelled()::get),job.key(),job.cancelled()::get),"Piece-theme scanner returned no review");
                if(!job.key().equals(review.key()))throw new IllegalArgumentException("Piece-theme scanner returned a different review key");
                outcome=new Outcome(job.request(),review,null);
            }
            catch (CancellationException cancelled) { outcome=new Outcome(job.request(),null,"Piece-theme scan cancelled; refresh to retry"); }
            catch (OutOfMemoryError exhausted) { outcome=new Outcome(job.request(),null,"Not enough memory to inspect piece themes; clear the review and refresh explicitly."); }
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
            if (closed) return;closed=true;invalidate();workspace=null;revision=-1;key=null;snapshot=new Snapshot(generation,Phase.CLOSED,null,null,null);lock.notifyAll();
        }
        worker.interrupt();
    }
}
