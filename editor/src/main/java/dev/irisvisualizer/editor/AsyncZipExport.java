package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;

/** One bounded snapshot/export at a time. Snapshot exports stay pinned across later edits, Save and New/Open. */
public final class AsyncZipExport implements AutoCloseable {
    public enum Phase { IDLE, EXPORTING, CANCELLING, PUBLISHING, SUCCEEDED, CANCELLED, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,ZipExportSource.Summary source,Path archive,boolean replace,int entries,long bytes,ZipExports.Receipt receipt,String detail) {}
    @FunctionalInterface interface Writer { ZipExports.Receipt write(ZipExportSource source,Path archive,boolean replace,ZipExports.Control control,ZipExportSource.Progress progress) throws IOException; }
    private static final class Job {
        final long request;final ZipExportSource source;final Path archive;final boolean replace;final ZipExports.Control control=new ZipExports.Control();volatile int entries;volatile long bytes;
        Job(long request,ZipExportSource source,Path archive,boolean replace) { this.request=request;this.source=source;this.archive=archive;this.replace=replace; }
    }
    private final Object lock=new Object();private final Writer writer;private final Thread worker;
    private long generation;private boolean closed;private Job pending,active;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null,false,0,0,null,null);
    public AsyncZipExport() { this(ZipExports::export,runnable -> new Thread(runnable,"iris-zip-export")); }
    AsyncZipExport(Writer writer,ThreadFactory threads) { this.writer=Objects.requireNonNull(writer);worker=Objects.requireNonNull(threads).newThread(this::run);if (worker==null) throw new IllegalArgumentException("ZIP worker factory returned no thread");worker.setDaemon(true);worker.start(); }
    public boolean busy() { synchronized (lock) { return pending!=null || active!=null; } }
    public Snapshot start(PackWorkspace current,Path archive,boolean replace) throws IOException {
        Objects.requireNonNull(current);Objects.requireNonNull(archive);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("ZIP export is closed");if (pending!=null || active!=null) throw new IOException("A ZIP export is active; wait for completion or cancellation before starting another");
            var source=current.captureZipExport();Path path=archive.toAbsolutePath().normalize();var job=new Job(++generation,source,path,replace);pending=job;snapshot=new Snapshot(generation,Phase.EXPORTING,source.summary(),path,replace,0,0,null,null);lock.notifyAll();return snapshot;
        }
    }
    public Snapshot poll() {
        synchronized (lock) {
            var job=active!=null ? active : pending;if (closed || job==null) return snapshot;
            Phase phase=job.control.publishing() ? Phase.PUBLISHING : job.control.cancelled() ? Phase.CANCELLING : Phase.EXPORTING;
            return new Snapshot(job.request,phase,job.source.summary(),job.archive,job.replace,job.entries,job.bytes,null,null);
        }
    }
    public boolean cancel() { synchronized (lock) { var job=active!=null ? active : pending;if (job==null || closed) return false;boolean accepted=job.control.cancel();if (accepted) worker.interrupt();return accepted; } }
    public void clear() { synchronized (lock) { if (closed) return;if (pending!=null || active!=null) throw new IllegalStateException("Wait for the active export before clearing its receipt");snapshot=new Snapshot(++generation,Phase.IDLE,null,null,false,0,0,null,null); } }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                Thread.interrupted();while (!closed && pending==null) try { lock.wait(); } catch (InterruptedException interrupted) { if (closed) return; }
                if (closed) return;active=job=pending;pending=null;
            }
            ZipExports.Receipt receipt=null;String detail=null;Phase phase;
            try { job.control.check();receipt=Objects.requireNonNull(writer.write(job.source,job.archive,job.replace,job.control,(entries,bytes) -> { job.entries=entries;job.bytes=bytes; }),"ZIP writer returned no receipt");job.control.check();phase=Phase.SUCCEEDED; }
            catch (CancellationException cancelled) { phase=Phase.CANCELLED;detail="ZIP export cancelled before publication. Captured data was not marked saved."; }
            catch (Exception failure) { if (job.control.cancelled()) { phase=Phase.CANCELLED;detail="ZIP export cancelled before publication."; } else { phase=Phase.FAILED;detail=InspectorText.label(Objects.toString(failure.getMessage(),"ZIP export failed ("+failure.getClass().getSimpleName()+")"),512); } }
            synchronized (lock) { active=null;if (!closed) snapshot=new Snapshot(job.request,phase,job.source.summary(),job.archive,job.replace,job.entries,job.bytes,phase==Phase.SUCCEEDED ? receipt : null,detail); }
        }
    }
    @Override public void close() { synchronized (lock) { if (closed) return;closed=true;if (active!=null) active.control.cancel();if (pending!=null) pending.control.cancel();pending=null;snapshot=new Snapshot(++generation,Phase.CLOSED,null,null,false,0,0,null,null);lock.notifyAll(); }worker.interrupt(); }
}
