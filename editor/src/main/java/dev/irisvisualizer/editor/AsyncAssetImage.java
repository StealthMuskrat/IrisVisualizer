package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/** UI-owned requests, one daemon decoder and one replaceable pending image; no worker workspace/GPU access. */
public final class AsyncAssetImage implements AutoCloseable {
    public enum Phase { NONE, LOADING, READY, FAILED, CLOSED }
    public record Snapshot(long request,String path,Phase phase,AssetImage.Decoded image,String error) {}
    @FunctionalInterface interface Decoder { AssetImage.Decoded decode(byte[] bytes) throws IOException; }
    private record Job(long request,byte[] bytes) {}
    private record Outcome(long request,AssetImage.Decoded image,String error) {}
    private final Object lock=new Object();
    private final Decoder decoder;
    private final Thread worker;
    private PackWorkspace workspace;
    private String path;
    private long contentRevision=-1,generation;
    private boolean closed;
    private Job pending;
    private Outcome completed;
    private Snapshot snapshot=new Snapshot(0,null,Phase.NONE,null,null);

    public AsyncAssetImage() { this(AssetImage::decode,runnable -> new Thread(runnable,"iris-image-decoder")); }
    AsyncAssetImage(Decoder decoder,ThreadFactory threads) {
        this.decoder=Objects.requireNonNull(decoder);worker=Objects.requireNonNull(threads).newThread(this::run);
        if (worker==null) throw new IllegalArgumentException("Image worker factory returned no thread");
        worker.setDaemon(true);worker.start();
    }
    /** Call on the UI thread. Only a changed selected file is copied; no decoder runs under this lock. */
    public Snapshot select(PackWorkspace current,String selected) { return select(current,selected,false); }
    public Snapshot retry(PackWorkspace current,String selected) { return select(current,selected,true); }
    private Snapshot select(PackWorkspace current,String selected,boolean force) {
        Objects.requireNonNull(current);
        boolean supported=AssetImage.supportedPath(selected),present=supported && current.paths().contains(selected);
        long stamp=present ? current.contentRevision(selected) : -1;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Image loader is closed");
            if (!force && workspace==current && Objects.equals(path,selected) && contentRevision==stamp) return pollLocked();
            workspace=current;path=selected;contentRevision=stamp;generation++;pending=null;completed=null;
            if (!supported) snapshot=new Snapshot(generation,selected,Phase.NONE,null,null);
            else if (!present) snapshot=new Snapshot(generation,selected,Phase.FAILED,null,"Image resource is not in this workspace");
            else if (current.size(selected)>AssetImage.MAX_ENCODED_BYTES) snapshot=new Snapshot(generation,selected,Phase.FAILED,null,"Asset image exceeds 16 MiB encoded limit");
            else {
                // PackWorkspace.read supplies the private bounded copy. The worker never receives the workspace.
                pending=new Job(generation,current.read(selected));snapshot=new Snapshot(generation,selected,Phase.LOADING,null,null);lock.notifyAll();
            }
            return snapshot;
        }
    }
    public Snapshot poll() { synchronized (lock) { return pollLocked(); } }
    private Snapshot pollLocked() {
        if (completed!=null && completed.request()==generation && snapshot.phase()==Phase.LOADING) {
            snapshot=new Snapshot(generation,path,completed.error()==null ? Phase.READY : Phase.FAILED,completed.image(),completed.error());completed=null;
        }
        return snapshot;
    }
    private void run() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (!closed && pending==null) {
                    try { lock.wait(); }
                    catch (InterruptedException interrupted) { if (closed) return; }
                }
                if (closed) return;job=pending;pending=null;
            }
            Outcome result;
            try { result=new Outcome(job.request(),Objects.requireNonNull(decoder.decode(job.bytes()),"Image decoder returned no pixels"),null); }
            catch (Exception failure) {
                String detail=failure.getMessage();if (detail==null || detail.isBlank()) detail="Image decode failed ("+failure.getClass().getSimpleName()+")";
                result=new Outcome(job.request(),null,InspectorText.label(detail,512));
            }
            synchronized (lock) {
                if (!closed && job.request()==generation) completed=result;
            }
        }
    }
    /** Invalidate immediately; the UI never joins a decoder that may ignore interruption. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;closed=true;generation++;pending=null;completed=null;workspace=null;path=null;contentRevision=-1;
            snapshot=new Snapshot(generation,null,Phase.CLOSED,null,null);lock.notifyAll();
        }
        worker.interrupt();
    }
}
