package dev.irisvisualizer.editor;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Explicit reviewed creation/duplication. Workers receive immutable bytes and metadata, never a workspace. */
public final class ReviewedResourceAddition implements AutoCloseable {
    public enum Mode { CREATE_JSON, DUPLICATE }
    public enum Phase { IDLE, PREPARING, READY, STALE, FAILED, APPLIED, CLOSED }
    public record Plan(Mode mode,String parent,String name,String dependency,String source,String target,int bytes) {}
    public record Receipt(String target,int bytes,String sha256,String excerpt) {}
    public record Snapshot(long request,Phase phase,Plan plan,Receipt receipt,String detail) {}
    /** Private immutable source-array reference: workspace replacement always installs a new array. */
    static final class Source {
        final Plan plan;private final byte[] bytes;
        Source(Plan plan,byte[] bytes) { this.plan=Objects.requireNonNull(plan);this.bytes=Objects.requireNonNull(bytes);if(plan.bytes()!=bytes.length)throw new IllegalArgumentException("Source length mismatch"); }
    }
    static final class Target {
        final PackWorkspace owner;final Source source;final long stamp;
        Target(PackWorkspace owner,Source source,long stamp) { this.owner=owner;this.source=source;this.stamp=stamp; }
    }
    static final class Prepared {
        final Source origin;final Receipt receipt;private byte[] data;private final byte[] hash;
        private Prepared(Source origin,byte[] data,byte[] hash,Receipt receipt) { this.origin=origin;this.data=data;this.hash=hash;this.receipt=receipt; }
        byte[] hashCopy() { return hash.clone(); }
        /** UI-owned one-shot ownership transfer, after complete destination/capacity preflight. */
        byte[] consume() throws IOException { if(data==null)throw new IOException("Reviewed bytes were already applied");byte[] owned=data;data=null;return owned; }
        boolean available() { return data!=null; }
    }
    public static final class Acknowledgement {
        private final PackWorkspace owner;private final Source source;private final long request,revision;
        private Acknowledgement(PackWorkspace owner,Source source,long request) { this.owner=owner;this.source=source;this.request=request;revision=owner.revision(); }
    }
    @FunctionalInterface interface Preparer { Prepared prepare(Source source,BooleanSupplier cancelled) throws Exception; }
    private record Job(long request,Source source,AtomicBoolean cancelled) {}
    private record Outcome(long request,Prepared prepared,String error) {}
    private final Object lock=new Object();private final Preparer preparer;private final Thread worker;
    private PackWorkspace owner;private Target target;private Prepared prepared;private Job active,pending;private Outcome completed;
    private long generation,checkedResources=-1;private boolean closed;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null,null);
    public ReviewedResourceAddition() { this(ReviewedResourceAddition::prepare,r->new Thread(r,"iris-resource-addition")); }
    ReviewedResourceAddition(Preparer preparer,ThreadFactory threads) { this.preparer=Objects.requireNonNull(preparer);worker=Objects.requireNonNull(threads).newThread(this::run);if(worker==null)throw new IllegalArgumentException("Resource preparation thread factory returned no thread");worker.setDaemon(true);worker.start(); }
    static Prepared prepare(Source source,BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(source);Objects.requireNonNull(cancelled);check(cancelled);var data=new byte[source.bytes.length];var digest=MessageDigest.getInstance("SHA-256");
        for(int offset=0;offset<data.length;offset+=8192) { check(cancelled);int size=Math.min(8192,data.length-offset);System.arraycopy(source.bytes,offset,data,offset,size);digest.update(data,offset,size); }
        check(cancelled);byte[] hash=digest.digest();String excerpt=source.plan.mode()==Mode.CREATE_JSON ? InspectorText.label(new String(data,0,Math.min(data.length,8192),java.nio.charset.StandardCharsets.UTF_8),2048) : null;
        return new Prepared(source,data,hash,new Receipt(source.plan.target(),data.length,HexFormat.of().formatHex(hash),excerpt));
    }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Resource preparation cancelled"); }
    private void invalidate() { generation++;if(active!=null)active.cancelled().set(true);if(pending!=null)pending.cancelled().set(true);pending=null;completed=null;prepared=null; }
    public Snapshot create(PackWorkspace current,String parent,String name,String dependency) throws IOException { return request(current,current.reviewResourceCreation(parent,name,dependency,PackWorkspace.MAX_BYTES)); }
    public Snapshot duplicate(PackWorkspace current,String source,String key) throws IOException { return request(current,current.reviewResourceDuplication(source,key,PackWorkspace.MAX_BYTES)); }
    private Snapshot request(PackWorkspace current,Target next) {
        synchronized(lock) { if(closed)throw new IllegalStateException("Resource review is closed");invalidate();owner=current;target=next;checkedResources=current.resourceRevision();snapshot=new Snapshot(generation,Phase.PREPARING,target.source.plan,null,null);pending=new Job(generation,next.source,new AtomicBoolean());lock.notifyAll();return snapshot; }
    }
    public Snapshot poll(PackWorkspace current) {
        Objects.requireNonNull(current);
        synchronized(lock) {
            if(closed)return snapshot;
            if(owner!=current) { invalidate();owner=current;target=null;checkedResources=-1;snapshot=new Snapshot(generation,Phase.IDLE,null,null,null); }
            if(target!=null && (!current.reviewedAdditionSourceCurrent(target) || checkedResources!=current.resourceRevision())) {
                try { current.checkReviewedAddition(target,PackWorkspace.MAX_BYTES);checkedResources=current.resourceRevision(); }
                catch(IOException stale) { var plan=target.source.plan;invalidate();target=null;snapshot=new Snapshot(generation,Phase.STALE,plan,null,InspectorText.label(stale.getMessage(),512)); }
            }
            if(completed!=null && completed.request()==generation && snapshot.phase()==Phase.PREPARING) { prepared=completed.prepared();snapshot=new Snapshot(generation,completed.error()==null ? Phase.READY : Phase.FAILED,target.source.plan,prepared==null ? null : prepared.receipt,completed.error());completed=null; }
            return snapshot;
        }
    }
    public Acknowledgement acknowledge(PackWorkspace current,long request) throws IOException { var state=poll(current);if(state.phase()!=Phase.READY || state.request()!=request)throw new IOException("Review the current prepared resource first");return new Acknowledgement(current,target.source,request); }
    public boolean acknowledged(PackWorkspace current,long request,Acknowledgement acknowledgement) { return acknowledgement!=null && acknowledgement.owner==current && target!=null && acknowledgement.source==target.source && acknowledgement.request==request && acknowledgement.revision==current.revision(); }
    public String apply(PackWorkspace current,long request,Acknowledgement acknowledgement) throws IOException { return apply(current,request,acknowledgement,PackWorkspace.MAX_BYTES); }
    String apply(PackWorkspace current,long request,Acknowledgement acknowledgement,long maximumBytes) throws IOException {
        var state=poll(current);if(state.phase()!=Phase.READY || state.request()!=request || target==null || target.owner!=current || prepared==null || prepared.origin!=target.source || !prepared.available())throw new IOException("No current reviewed resource is ready; prepare again");
        if(current.undoCount()+current.redoCount()>0 && !acknowledged(current,request,acknowledgement))throw new IOException("Acknowledge clearing the current applied-edit history before applying");
        current.checkReviewedAddition(target,maximumBytes);String path=current.installReviewedAddition(target,prepared,maximumBytes);var receipt=prepared.receipt;var plan=target.source.plan;
        synchronized(lock) { invalidate();target=null;snapshot=new Snapshot(generation,Phase.APPLIED,plan,receipt,"Resource added in memory; applied-edit history cleared. Save remains explicit.");return path; }
    }
    public void clear() { synchronized(lock) { if(closed)return;invalidate();owner=null;target=null;checkedResources=-1;snapshot=new Snapshot(generation,Phase.IDLE,null,null,null); }worker.interrupt(); }
    private void run() {
        while(true) {
            Job job;synchronized(lock) { Thread.interrupted();while(!closed && pending==null)try { lock.wait(); }catch(InterruptedException interrupted) { if(closed)return; }if(closed)return;active=job=pending;pending=null; }
            Outcome outcome;try { var result=Objects.requireNonNull(preparer.prepare(job.source(),job.cancelled()::get),"Resource preparer returned no bytes");if(result.origin!=job.source())throw new IOException("Prepared resource belongs to another request");outcome=new Outcome(job.request(),result,null); }
            catch(CancellationException cancelled) { outcome=new Outcome(job.request(),null,"Resource preparation cancelled; prepare again explicitly."); }
            catch(OutOfMemoryError exhausted) { outcome=new Outcome(job.request(),null,"Not enough memory to prepare this resource; clear the review and choose a smaller resource."); }
            catch(Exception failure) { String detail=failure.getMessage();if(detail==null || detail.isBlank())detail="Resource preparation failed ("+failure.getClass().getSimpleName()+")";outcome=new Outcome(job.request(),null,InspectorText.label(detail,512)); }
            synchronized(lock) { active=null;if(!closed && !job.cancelled().get() && job.request()==generation)completed=outcome; }
        }
    }
    @Override public void close() { synchronized(lock) { if(closed)return;closed=true;invalidate();owner=null;target=null;snapshot=new Snapshot(generation,Phase.CLOSED,null,null,null);lock.notifyAll(); }worker.interrupt(); }
}
