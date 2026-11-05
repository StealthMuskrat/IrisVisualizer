package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable completed scalar image and exact original sample/display/camera receipt. */
public final class GlNoiseFrame implements AutoCloseable {
    public record Info(UUID id,long revision,UUID volumeId,MapSamples.Context context,NoiseVolume.Grid grid,NoiseSurface.Style style,GlCamera camera,int width,int height,GlNoiseRenderer.Stats stats,WorldClip clip) {
        public Info(UUID id,long revision,UUID volumeId,MapSamples.Context context,NoiseVolume.Grid grid,NoiseSurface.Style style,GlCamera camera,int width,int height,GlNoiseRenderer.Stats stats) { this(id,revision,volumeId,context,grid,style,camera,width,height,stats,WorldClip.NONE); }
        public Info { Objects.requireNonNull(clip);Objects.requireNonNull(id);Objects.requireNonNull(volumeId);Objects.requireNonNull(context);Objects.requireNonNull(grid);Objects.requireNonNull(style);Objects.requireNonNull(camera);Objects.requireNonNull(stats);if(revision<1||width<1||height<1)throw new IllegalArgumentException("Invalid scalar frame receipt"); }
        public WorldRay ray(double x,double y) { return camera.ray(x,y,width,height); }
        public NoisePick.Options pickOptions(NoisePick.Options options) { return Objects.requireNonNull(options).withClip(clip); }
        public NoisePickWorker.Frame pickFrame() { return new NoisePickWorker.Frame(id,revision); }
        public boolean matches(NoisePickWorker.Completion completion) {
            Objects.requireNonNull(completion);
            return completion.frame().filter(pickFrame()::equals).isPresent()&&volumeId.equals(completion.result().volumeId())&&clip.equals(completion.result().clip());
        }
    }
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation journal;final GlTarget target;final GlNoiseScene scene;final Info info;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation journal,GlTarget target,GlNoiseScene scene,Info info) { this.device=device;this.journal=journal;this.target=target;this.scene=scene;this.info=info; }
    }
    private final Info info;private Shared shared;
    private GlNoiseFrame(Shared shared) { this.shared=shared;info=shared.info; }
    private static void check(BooleanSupplier obsolete) { if(obsolete.getAsBoolean())throw new CancellationException("Scalar frame is obsolete"); }
    public static GlNoiseFrame render(GlNoiseRenderer renderer,long revision,GlNoiseScene source,GlCamera camera,int width,int height,BooleanSupplier obsolete) {
        return render(renderer,revision,source,camera,width,height,WorldClip.NONE,obsolete);
    }
    public static GlNoiseFrame render(GlNoiseRenderer renderer,long revision,GlNoiseScene source,GlCamera camera,int width,int height,WorldClip clip,BooleanSupplier obsolete) {
        Objects.requireNonNull(clip);Objects.requireNonNull(renderer);Objects.requireNonNull(source);Objects.requireNonNull(camera);Objects.requireNonNull(obsolete);if(revision<1)throw new IllegalArgumentException("Invalid scalar frame revision");
        var device=renderer.device;device.live(renderer.allocation);if(source.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Scalar frame inputs must share one device");var surface=source.surface();camera.matrix((double)width/height);check(obsolete);
        GlNoiseScene owner=null;GlDevice.Allocation journal=null;GlTarget target=null;
        try {
            owner=source.retain();journal=device.reserve(0,0,0,0,0,0,0,0,0,null);device.depend(journal,source.allocation);check(obsolete);target=GlTarget.create(device,width,height);device.depend(journal,target.allocation);check(obsolete);
            var stats=renderer.render(owner,target,camera,clip);check(obsolete);var volume=surface.volume();var receipt=new Info(UUID.randomUUID(),revision,volume.id(),volume.context(),volume.grid(),surface.style(),camera,width,height,stats,clip);return new GlNoiseFrame(new Shared(device,journal,target,owner,receipt));
        }catch(RuntimeException|Error failure) {
            if(journal!=null)try { device.retire(journal); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(target!=null)try { target.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(owner!=null)try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    private Shared live() { var value=shared;if(value==null||value.closing)throw new GlFailure(GlFailure.Code.STATE,"Scalar frame owner is closed or closing");value.device.live(value.journal);value.device.live(value.target.allocation);return value; }
    public Info info() { return info; }
    public boolean closed() { return shared==null; }
    /** Borrowed output texture; flip V for a top-left widget. */
    public int textureId() { return live().target.textureId(); }
    /** Capture on the context thread, then keep this frame owner live throughout off-thread immutable inspection. */
    public NoiseSurface surface() { return live().scene.surface(); }
    /** Context-thread capture; the independently admitted CPU surface survives frame/device closure. */
    public RenderInput<NoiseSurface> retainSurfaceInput() { return live().scene.retainInput(); }
    /** Context-thread submission only; the admitted CPU request survives independent frame closure. */
    public long submitPick(NoisePickWorker worker,double x,double y,NoisePick.Options options) {
        Objects.requireNonNull(worker);Objects.requireNonNull(options);var value=live();
        if(value.device.memory().orElse(null)!=worker.memory().orElse(null))
            throw new IllegalArgumentException("Scalar frame and picking worker must share their memory coordinator");
        return worker.submit(new NoisePickWorker.Request(value.scene.surface(),info.ray(x,y),info.pickOptions(options),Optional.of(info.pickFrame())));
    }
    GlTarget readbackTarget() { return live().target; }
    GlDevice.Allocation readbackJournal() { return live().journal; }
    public GlNoiseFrame retain() { var value=live();if(value.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"Scalar frame owner count reached");var result=new GlNoiseFrame(value);value.references++;return result; }
    @Override public void close() {
        var value=shared;if(value==null)return;if(!value.journal.released||!value.target.allocation.released||!value.scene.allocation.released)value.device.current();
        if(value.references>1) { value.references--;shared=null;return; }value.closing=true;Throwable failure=null;
        try { value.device.retire(value.journal); }catch(RuntimeException|Error error) { failure=error; }
        try { value.target.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { value.scene.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if((value.journal.retired||value.journal.released)&&(value.target.allocation.retired||value.target.allocation.released)&&value.scene.closed()) { value.references=0;shared=null; }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
