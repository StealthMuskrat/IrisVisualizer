package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;

/** One bounded snapshot/copy at a time. Snapshot copies stay pinned across later edits, Save and New/Open. */
public final class AsyncFolderCopy implements AutoCloseable {
    public enum Phase { IDLE, COPYING, CANCELLING, PUBLISHING, SUCCEEDED, CANCELLED, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,ZipExportSource.Summary source,Path directory,int entries,long bytes,FolderCopies.Receipt receipt,String detail) {}
    @FunctionalInterface interface Writer { FolderCopies.Receipt write(ZipExportSource source,Path directory,ZipExports.Control control,ZipExportSource.Progress progress) throws IOException; }
    private static final class Job {
        final long request;final ZipExportSource source;final Path directory;final ZipExports.Control control=new ZipExports.Control();volatile int entries;volatile long bytes;
        Job(long request,ZipExportSource source,Path directory) { this.request=request;this.source=source;this.directory=directory; }
    }
    private final Object lock=new Object();private final Writer writer;private final Thread worker;
    private long generation;private boolean closed;private Job pending,active;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null,0,0,null,null);
    public AsyncFolderCopy() { this(FolderCopies::copy,runnable -> new Thread(runnable,"iris-folder-copy")); }
    AsyncFolderCopy(Writer writer,ThreadFactory threads) { this.writer=Objects.requireNonNull(writer);worker=Objects.requireNonNull(threads).newThread(this::run);if (worker==null) throw new IllegalArgumentException("Folder-copy worker factory returned no thread");worker.setDaemon(true);worker.start(); }
    public boolean busy() { synchronized (lock) { return pending!=null || active!=null; } }
    public Snapshot start(PackWorkspace current,Path directory) throws IOException {
        Objects.requireNonNull(current);Objects.requireNonNull(directory);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Folder copy is closed");if (pending!=null || active!=null) throw new IOException("A folder copy is active; wait for completion or cancellation before starting another");
            var source=current.captureZipExport();Path path=directory.toAbsolutePath().normalize();var job=new Job(++generation,source,path);pending=job;snapshot=new Snapshot(generation,Phase.COPYING,source.summary(),path,0,0,null,null);lock.notifyAll();return snapshot;
        }
    }
    public Snapshot poll() {
        synchronized (lock) {
            var job=active!=null ? active : pending;if (closed || job==null) return snapshot;
            Phase phase=job.control.publishing() ? Phase.PUBLISHING : job.control.cancelled() ? Phase.CANCELLING : Phase.COPYING;
            return new Snapshot(job.request,phase,job.source.summary(),job.directory,job.entries,job.bytes,null,null);
        }
    }
    public boolean cancel() { synchronized (lock) { var job=active!=null ? active : pending;if (job==null || closed) return false;boolean accepted=job.control.cancel();if (accepted) worker.interrupt();return accepted; } }
    public void clear() { synchronized (lock) { if (closed) return;if (pending!=null || active!=null) throw new IllegalStateException("Wait for the active copy before clearing its receipt");snapshot=new Snapshot(++generation,Phase.IDLE,null,null,0,0,null,null); } }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                Thread.interrupted();while (!closed && pending==null) try { lock.wait(); } catch (InterruptedException interrupted) { if (closed) return; }
                if (closed) return;active=job=pending;pending=null;
            }
            FolderCopies.Receipt receipt=null;String detail=null;Phase phase;
            try { job.control.check();receipt=Objects.requireNonNull(writer.write(job.source,job.directory,job.control,(entries,bytes) -> { job.entries=entries;job.bytes=bytes; }),"Folder-copy writer returned no receipt");job.control.check();phase=Phase.SUCCEEDED; }
            catch (CancellationException cancelled) { phase=Phase.CANCELLED;detail="Folder copy cancelled before publication. Captured data was not marked saved."; }
            catch (Exception failure) { if (job.control.cancelled()) { phase=Phase.CANCELLED;detail=InspectorText.label("Folder copy cancelled before publication; cleanup/I/O detail: "+Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()),512); } else { phase=Phase.FAILED;detail=InspectorText.label(Objects.toString(failure.getMessage(),"Folder copy failed ("+failure.getClass().getSimpleName()+")"),512); } }
            synchronized (lock) { active=null;if (!closed) snapshot=new Snapshot(job.request,phase,job.source.summary(),job.directory,job.entries,job.bytes,phase==Phase.SUCCEEDED ? receipt : null,detail); }
        }
    }
    @Override public void close() { synchronized (lock) { if (closed) return;closed=true;if (active!=null) active.control.cancel();if (pending!=null) pending.control.cancel();pending=null;snapshot=new Snapshot(++generation,Phase.CLOSED,null,null,0,0,null,null);lock.notifyAll(); }worker.interrupt(); }
}
