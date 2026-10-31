package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Explicit read-only analysis over one immutable decoded image; one active job and latest pending region. */
public final class AsyncImageStatistics implements AutoCloseable {
    public enum Phase { IDLE, ANALYZING, READY, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,ImageStatistics.Region region,long processed,ImageStatistics.Result result,String error) {}
    @FunctionalInterface interface Analyzer { ImageStatistics.Result analyze(AssetImage.Decoded image,ImageStatistics.Region region,BooleanSupplier cancelled,LongConsumer progress); }
    private static final class Job {
        final long request;final AssetImage.Decoded image;final ImageStatistics.Region region;final AtomicBoolean cancelled=new AtomicBoolean();volatile long processed;
        Job(long request,AssetImage.Decoded image,ImageStatistics.Region region) { this.request=request;this.image=image;this.region=region; }
    }
    private final Object lock=new Object();private final Analyzer analyzer;private final Thread worker;
    private AssetImage.Decoded owner;private Job active,pending;private long generation;private boolean closed;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,0,null,null);
    public AsyncImageStatistics() { this(ImageStatistics::analyze,r->new Thread(r,"iris-image-statistics")); }
    AsyncImageStatistics(Analyzer analyzer,ThreadFactory threads) {
        this.analyzer=Objects.requireNonNull(analyzer);worker=Objects.requireNonNull(threads).newThread(this::run);if(worker==null)throw new IllegalArgumentException("Image statistics thread factory returned no thread");worker.setDaemon(true);worker.start();
    }
    private void invalidate() { generation++;if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null; }
    /** Invalid regions fail before replacing accepted work; image bytes are neither copied nor exposed. */
    public Snapshot start(AssetImage.Decoded image,ImageStatistics.Region region) {
        Objects.requireNonNull(image);Objects.requireNonNull(region);region.validate(image.width(),image.height());
        synchronized(lock) { if(closed)throw new IllegalStateException("Image statistics is closed");invalidate();owner=image;pending=new Job(generation,image,region);snapshot=new Snapshot(generation,Phase.ANALYZING,region,0,null,null);lock.notifyAll();return snapshot; }
    }
    /** Image identity changes discard receipts without an automatic analysis. Null releases the current binding. */
    public Snapshot poll(AssetImage.Decoded image) {
        synchronized(lock) {
            if(closed)return snapshot;
            if(owner!=image) { invalidate();owner=image;snapshot=new Snapshot(generation,Phase.IDLE,null,0,null,null); }
            var job=active!=null && active.request==generation ? active : pending;
            if(snapshot.phase()==Phase.ANALYZING && job!=null)return new Snapshot(generation,Phase.ANALYZING,job.region,job.processed,null,null);
            return snapshot;
        }
    }
    public void clear() { synchronized(lock) { if(closed)return;invalidate();owner=null;snapshot=new Snapshot(generation,Phase.IDLE,null,0,null,null); }worker.interrupt(); }
    private void run() {
        while(true) {
            Job job;
            synchronized(lock) { Thread.interrupted();while(!closed && pending==null)try { lock.wait(); }catch(InterruptedException interrupted) { if(closed)return; }if(closed)return;active=job=pending;pending=null; }
            ImageStatistics.Result result=null;String error=null;
            try { result=Objects.requireNonNull(analyzer.analyze(job.image,job.region,job.cancelled::get,pixels->job.processed=pixels),"Image analyzer returned no result");if(!result.region().equals(job.region))throw new IllegalArgumentException("Image analyzer returned a different region"); }
            catch(CancellationException cancelled) { error="Image statistics cancelled; analyze again explicitly."; }
            catch(Exception failure) { String detail=failure.getMessage();if(detail==null || detail.isBlank())detail="Image analysis failed ("+failure.getClass().getSimpleName()+")";error=InspectorText.label(detail,512); }
            synchronized(lock) { active=null;if(!closed && !job.cancelled.get() && job.request==generation)snapshot=new Snapshot(generation,error==null ? Phase.READY : Phase.FAILED,job.region,job.processed,error==null ? result : null,error); }
        }
    }
    @Override public void close() { synchronized(lock) { if(closed)return;closed=true;invalidate();owner=null;snapshot=new Snapshot(generation,Phase.CLOSED,null,0,null,null);lock.notifyAll(); }worker.interrupt(); }
}
