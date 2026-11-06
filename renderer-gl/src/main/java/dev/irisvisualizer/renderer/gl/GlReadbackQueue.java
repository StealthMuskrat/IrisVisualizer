package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.RenderInput;
import java.util.*;
import java.util.function.Supplier;

/** One latest-revision readback, including its charged untaken CPU output. Source images stay caller-owned. */
public final class GlReadbackQueue implements AutoCloseable {
    public enum Phase { IDLE, WAITING, READING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<GlReadback.Progress> progress,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(progress);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    public record Completion(long revision,GlReadback.Pixels pixels) { public Completion { if(revision<1)throw new IllegalArgumentException("Invalid readback revision");Objects.requireNonNull(pixels); } }
    /** The packet is borrowed from input; retain the input before sharing it with another owner. */
    public record OwnedCompletion(long revision,RenderInput<GlReadback.Pixels> input) implements AutoCloseable {
        public OwnedCompletion { if(revision<1)throw new IllegalArgumentException("Invalid readback revision");Objects.requireNonNull(input); }
        public GlReadback.Pixels pixels() { return input.value(); }
        @Override public void close() { input.close(); }
    }
    private final GlDevice device;private final GlReadback.Limits limits;private long revision;private GlReadback active;private boolean closed,disposed;
    private volatile Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),"");
    public GlReadbackQueue(GlDevice device) { this(device,GlReadback.Limits.DEFAULT); }
    public GlReadbackQueue(GlDevice device,GlReadback.Limits limits) { this.device=Objects.requireNonNull(device);this.limits=Objects.requireNonNull(limits);device.current(); }
    public Status status() { return status; }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"Readback queue is closed"); }
    private Status state(Phase phase,GlFailure.Code code,String reason) { return new Status(revision,phase,active==null?Optional.empty():Optional.of(active.progress()),Optional.ofNullable(code),reason); }
    private void failed(Throwable error) {
        try {
            String reason=Objects.toString(error.getMessage(),error.getClass().getSimpleName());if(reason.length()>8192)reason=reason.substring(0,8192);
            status=state(Phase.FAILED,error instanceof GlFailure gpu?gpu.code():error instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE,reason);
        }catch(RuntimeException|Error publication) { if(publication!=error)error.addSuppressed(publication); }
    }
    private void releaseOwned() { if(active!=null)try { active.close();active=null; }catch(RuntimeException|Error error) { if(!active.ownsResources())active=null;throw error; } }
    public boolean invalidate(long next) {
        current();if(next<1)throw new IllegalArgumentException("Invalid readback revision");if(next<=revision)return false;var waiting=new Status(next,Phase.WAITING,Optional.empty(),Optional.empty(),"");revision=next;status=waiting;
        try { releaseOwned();return true; }catch(RuntimeException|Error error) { failed(error);throw error; }
    }
    /** Reopen only the exact failed token after cleanup; the caller resubmits a retained complete frame. */
    public boolean retry(long expectedRevision) {
        current();if(expectedRevision<1)throw new IllegalArgumentException("Invalid readback revision");if(expectedRevision!=revision||status.phase()!=Phase.FAILED)return false;
        var waiting=new Status(revision,Phase.WAITING,Optional.empty(),Optional.empty(),"");
        try { releaseOwned();status=waiting;return true; }catch(RuntimeException|Error error) { failed(error);throw error; }
    }
    public boolean submit(long next,GlFrame frame) { Objects.requireNonNull(frame);return submit(next,frame::readbackTarget,()->GlReadback.begin(frame,limits)); }
    public boolean submit(long next,GlMapFrame frame) { Objects.requireNonNull(frame);return submit(next,frame::readbackTarget,()->GlReadback.begin(frame,limits)); }
    public boolean submit(long next,GlNoiseFrame frame) { Objects.requireNonNull(frame);return submit(next,frame::readbackTarget,()->GlReadback.begin(frame,limits)); }
    public boolean submit(long next,GlImageFrame frame) { Objects.requireNonNull(frame);return submit(next,frame::readbackTarget,()->GlReadback.begin(frame,limits)); }
    private boolean submit(long next,Supplier<GlTarget> source,Supplier<GlReadback> begin) {
        current();if(next<1)throw new IllegalArgumentException("Invalid readback revision");if(next<revision||next==revision&&status.phase()!=Phase.WAITING)return false;
        var target=source.get();if(target.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Readback image and queue must share one device");GlReadback.validate(target,limits);
        GlReadback fresh=null;Throwable rejection=null;
        try {
            fresh=begin.get();
            if(fresh.device()!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Readback image and queue must share one device");
            var reading=new Status(next,Phase.READING,Optional.of(fresh.progress()),Optional.empty(),"");
            try { releaseOwned(); }catch(RuntimeException|Error error) { failed(error);throw error; }
            active=fresh;fresh=null;revision=next;status=reading;return true;
        }catch(RuntimeException|Error error) { rejection=error;throw error; }
        finally { if(fresh!=null)try { fresh.close(); }catch(RuntimeException|Error cleanup) { if(rejection==null)throw cleanup;if(cleanup!=rejection)rejection.addSuppressed(cleanup); } }
    }
    public Status advance(GlUploadBudget budget,long latestRevision) {
        Objects.requireNonNull(budget);current();if(latestRevision<1)throw new IllegalArgumentException("Invalid readback revision");if(latestRevision>revision) { invalidate(latestRevision);return status; }if(latestRevision<revision||status.phase()!=Phase.READING||active==null)return status;
        try { active.step(budget,()->false);status=state(active.ready()?Phase.READY:Phase.READING,null,"");return status; }
        catch(RuntimeException|Error error) { try { releaseOwned(); }catch(RuntimeException|Error cleanup) { if(cleanup!=error)error.addSuppressed(cleanup); }failed(error);throw error; }
    }
    public Optional<Completion> take(long latestRevision) {
        current();if(latestRevision<1)throw new IllegalArgumentException("Invalid readback revision");if(latestRevision>revision) { invalidate(latestRevision);return Optional.empty(); }if(latestRevision!=revision||status.phase()!=Phase.READY||active==null||!active.ready())return Optional.empty();
        // Metadata allocation has not consumed output; setup failure leaves READY pixels and their journal intact.
        var result=Optional.of(new Completion(revision,active.readyPixels()));var delivered=new Status(revision,Phase.DELIVERED,status.progress(),Optional.empty(),"");
        try {
            active.take();active.close();active=null;status=delivered;return result;
        }catch(RuntimeException|Error error) { try { releaseOwned(); }catch(RuntimeException|Error cleanup) { if(cleanup!=error)error.addSuppressed(cleanup); }failed(error);throw error; }
    }
    /** Admission/setup rejection preserves READY output; successful transfer leaves no queue-owned CPU pixels. */
    public Optional<OwnedCompletion> takeInput(long latestRevision) {
        current();if(latestRevision<1)throw new IllegalArgumentException("Invalid readback revision");if(latestRevision>revision) { invalidate(latestRevision);return Optional.empty(); }if(latestRevision!=revision||status.phase()!=Phase.READY||active==null||!active.ready())return Optional.empty();
        var input=active.retainReadyInput();boolean transferring=false;
        try {
            var result=Optional.of(new OwnedCompletion(revision,input));var delivered=new Status(revision,Phase.DELIVERED,status.progress(),Optional.empty(),"");
            transferring=true;active.take();active.close();active=null;status=delivered;return result;
        }catch(RuntimeException|Error error) {
            try { input.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=error)error.addSuppressed(cleanup); }
            if(transferring) { try { releaseOwned(); }catch(RuntimeException|Error cleanup) { if(cleanup!=error)error.addSuppressed(cleanup); }failed(error); }
            throw error;
        }
    }
    @Override public void close() {
        if(disposed)return;device.current();closed=true;Throwable failure=null;
        try { releaseOwned(); }catch(RuntimeException|Error error) { failure=error; }
        try { status=new Status(revision,Phase.CLOSED,Optional.empty(),Optional.empty(),""); }
        catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        disposed=failure==null;
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
