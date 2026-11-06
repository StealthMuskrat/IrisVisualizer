package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** Render-context latest-revision upload/adoption. No extra thread or pending CPU-result queue. */
public final class GlUploadQueue implements AutoCloseable {
    public enum Phase { IDLE, WAITING, UPLOADING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<WorldSnapshot.Source> source,Optional<GlUpload.Progress> progress,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);Objects.requireNonNull(progress);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    public record Completion(long revision,GlScene scene) implements AutoCloseable {
        public Completion { if(revision<1)throw new IllegalArgumentException("Invalid upload revision");Objects.requireNonNull(scene); }
        @Override public void close() { scene.close(); }
    }
    private final GlRenderer renderer;private final GlDevice device;private long revision;private GlUpload active;private GlScene completed;private boolean closed,disposed;
    private volatile Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),Optional.empty(),"");
    public GlUploadQueue(GlRenderer renderer) { this.renderer=Objects.requireNonNull(renderer);device=renderer.device;device.live(renderer.allocation); }
    public Status status() { return status; }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"Upload queue is closed"); }
    /** Invalidate on a newer edit even before its CPU scene is available. */
    public boolean invalidate(long next) {
        current();if(next<1)throw new IllegalArgumentException("Invalid request revision");if(next<=revision)return false;
        var waiting=new Status(next,Phase.WAITING,Optional.empty(),Optional.empty(),Optional.empty(),"");revision=next;status=waiting;
        try { releaseOwned(); }catch(RuntimeException|Error failure) { failed(failure);throw failure; }return true;
    }
    /** Reopen only the current failed upload for a fresh CPU owner; successful/transferred work is unchanged. */
    public boolean retry(long expectedRevision) {
        current();if(expectedRevision<1)throw new IllegalArgumentException("Invalid request revision");
        if(expectedRevision!=revision||status.phase()!=Phase.FAILED)return false;return restart(expectedRevision);
    }
    /** The owning world view may abandon partial work after its own source-adoption failure. */
    boolean restart(long expectedRevision) {
        current();if(expectedRevision<1)throw new IllegalArgumentException("Invalid request revision");if(expectedRevision!=revision)return false;
        var waiting=new Status(revision,Phase.WAITING,Optional.empty(),Optional.empty(),Optional.empty(),"");
        try { releaseOwned();status=waiting;return true; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Fresh leases are consumed; initial admission rejection preserves accepted work. Device-owned leases remain intact. */
    public boolean submit(long next,WorldScene.Result owner) {
        return submit(next,owner,null);
    }
    public boolean submit(long next,WorldScene.Result owner,WorldTint tint) { return submit(next,owner,tint,null); }
    public boolean submit(long next,WorldScene.Result owner,WorldTint tint,WorldLighting lighting) {
        Objects.requireNonNull(owner);boolean handedOff=false;Throwable rejection=null;GlUpload fresh=null;
        try {
            current();
            if(device.ownsCpu(owner)) { handedOff=true;return false; }
            if(next<1)throw new IllegalArgumentException("Invalid request revision");
            if(next<revision||next==revision&&status.phase()!=Phase.WAITING)return false;
            tint=GlUpload.validate(renderer,owner,tint,lighting);
            // A fresh journal must fit alongside old work before any accepted token/source is changed.
            handedOff=true;fresh=GlUpload.begin(renderer,owner,tint,lighting);
            install(next,fresh);fresh=null;return true;
        }catch(RuntimeException|Error failure) { rejection=failure;throw failure; }
        finally {
            if(fresh!=null)try { fresh.close(); }
            catch(RuntimeException|Error cleanup) { if(rejection==null)throw cleanup;if(cleanup!=rejection)rejection.addSuppressed(cleanup); }
            if(!handedOff)try { owner.close(); }
            catch(RuntimeException|Error cleanup) { if(rejection==null)throw cleanup;if(cleanup!=rejection)rejection.addSuppressed(cleanup); }
        }
    }
    /** Borrow a complete source and exact replacement materials; initial admission preserves previous queue work. */
    public boolean materials(long next,GlScene source,WorldTint tint,WorldLighting lighting) { return materials(next,source,null,tint,lighting); }
    public boolean materials(long next,GlScene source,GlAtlasFrame atlas,WorldTint tint,WorldLighting lighting) {
        Objects.requireNonNull(source);current();if(next<1)throw new IllegalArgumentException("Invalid request revision");if(next<revision||next==revision&&status.phase()!=Phase.WAITING)return false;
        GlUpload fresh=null;Throwable rejection=null;
        try { fresh=GlUpload.materials(renderer,source,atlas,tint,lighting);install(next,fresh);fresh=null;return true; }
        catch(RuntimeException|Error failure) { rejection=failure;throw failure; }
        finally { if(fresh!=null)try { fresh.close(); }catch(RuntimeException|Error cleanup) { if(rejection==null)throw cleanup;if(cleanup!=rejection)rejection.addSuppressed(cleanup); } }
    }
    /** Consume fresh lighting on every outcome; finish owned-input cleanup before queue replacement. */
    public boolean materialsLit(long next,GlScene source,WorldTint tint,RenderInput<WorldLighting> input) { return materialsLit(next,source,null,tint,input); }
    public boolean materialsLit(long next,GlScene source,GlAtlasFrame atlas,WorldTint tint,RenderInput<WorldLighting> input) {
        Objects.requireNonNull(input);GlUpload fresh=null;Throwable rejection=null;
        try {
            try(input) {
                Objects.requireNonNull(source);current();if(next<1)throw new IllegalArgumentException("Invalid request revision");if(next<revision||next==revision&&status.phase()!=Phase.WAITING)return false;
                fresh=GlUpload.materialsLit(renderer,source,atlas,tint,input);
            }
            install(next,fresh);fresh=null;return true;
        }catch(RuntimeException|Error failure) { rejection=failure;throw failure; }
        finally { if(fresh!=null)try { fresh.close(); }catch(RuntimeException|Error cleanup) { if(rejection==null)throw cleanup;if(cleanup!=rejection)rejection.addSuppressed(cleanup); } }
    }
    private void install(long next,GlUpload fresh) {
        var queued=new Status(next,Phase.UPLOADING,Optional.of(fresh.source()),Optional.of(fresh.progress()),Optional.empty(),"");
        try { releaseOwned(); }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
        active=fresh;revision=next;status=queued;
    }
    /** The caller supplies its current CPU/edit revision each frame; obsolete work is never advanced. */
    public Status advance(GlUploadBudget budget,long latestRevision) {
        Objects.requireNonNull(budget);current();if(latestRevision<1)throw new IllegalArgumentException("Invalid request revision");
        if(latestRevision>revision) { invalidate(latestRevision);return status; }
        if(latestRevision<revision||status.phase()!=Phase.UPLOADING||active==null)return status;
        try {
            var progress=active.step(budget,()->false).progress();
            if(active.ready()) { completed=active.take();active.close();active=null;status=new Status(revision,Phase.READY,status.source(),Optional.of(progress),Optional.empty(),""); }
            else status=new Status(revision,Phase.UPLOADING,status.source(),Optional.of(progress),Optional.empty(),"");return status;
        }catch(RuntimeException|Error failure) {
            try { releaseOwned(); }catch(RuntimeException|Error retirement) { if(retirement!=failure)failure.addSuppressed(retirement); }failed(failure);throw failure;
        }
    }
    /** Exactly-once GPU ownership transfer, guarded again against the caller's latest revision. */
    public Optional<Completion> take(long latestRevision) {
        current();if(latestRevision<1)throw new IllegalArgumentException("Invalid request revision");if(latestRevision>revision) { invalidate(latestRevision);return Optional.empty(); }
        if(latestRevision!=revision||status.phase()!=Phase.READY||completed==null)return Optional.empty();var value=Optional.of(new Completion(revision,completed));var delivered=new Status(revision,Phase.DELIVERED,status.source(),status.progress(),Optional.empty(),"");completed=null;status=delivered;return value;
    }
    private void failed(Throwable failure) {
        try {
            var code=failure instanceof GlFailure gpu?gpu.code():failure instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE;
            String reason=failure.getMessage();if(reason==null)reason=failure.getClass().getSimpleName();if(reason.length()>8192)reason=reason.substring(0,8192);
            status=new Status(revision,Phase.FAILED,status.source(),status.progress(),Optional.of(code),reason);
        }catch(RuntimeException|Error publication) { if(publication!=failure)failure.addSuppressed(publication); }
    }
    private void releaseOwned() {
        Throwable failure=null;
        if(active!=null)try { active.close();active=null; }catch(RuntimeException|Error error) { if(!active.ownsResources())active=null;failure=error; }
        if(completed!=null)try { completed.close();completed=null; }catch(RuntimeException|Error error) { if(completed.allocation.retired||completed.allocation.released)completed=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    /** Caller-transferred completions stay caller-owned; only queue-owned uploads/scenes are retired. */
    @Override public void close() {
        if(disposed)return;device.current();closed=true;Throwable failure=null;
        try { releaseOwned(); }catch(RuntimeException|Error error) { failure=error; }
        try { status=new Status(revision,Phase.CLOSED,Optional.empty(),Optional.empty(),Optional.empty(),""); }
        catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        disposed=failure==null;
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
