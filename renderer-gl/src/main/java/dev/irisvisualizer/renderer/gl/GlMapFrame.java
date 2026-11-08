package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Independently owned completed map canvas, original samples and exact display transform. */
public final class GlMapFrame implements AutoCloseable {
    public record Info(UUID id,long revision,MapSamples.Context context,MapSamples.Kind kind,MapSamples.Grid grid,MapView view,int width,int height,Optional<MapRaster.Style> style,Optional<MapRaster.Summary> summary,Optional<ScalarHistogram.Receipt> scalarSource) {
        public Info(UUID id,long revision,MapSamples.Context context,MapSamples.Kind kind,MapSamples.Grid grid,MapView view,int width,int height,Optional<MapRaster.Style> style,Optional<MapRaster.Summary> summary) { this(id,revision,context,kind,grid,view,width,height,style,summary,Optional.empty()); }
        /** Legacy receipts explicitly leave colorization metadata unknown instead of inventing a style. */
        public Info(UUID id,long revision,MapSamples.Context context,MapSamples.Kind kind,MapSamples.Grid grid,MapView view,int width,int height) { this(id,revision,context,kind,grid,view,width,height,Optional.empty(),Optional.empty()); }
        public Info {
            Objects.requireNonNull(scalarSource);Objects.requireNonNull(style);Objects.requireNonNull(summary);Objects.requireNonNull(id);Objects.requireNonNull(context);Objects.requireNonNull(kind);Objects.requireNonNull(grid);Objects.requireNonNull(view);
            if(revision<1||width<1||height<1||view.width()!=grid.width()||view.height()!=grid.height()||style.isPresent()!=summary.isPresent())throw new IllegalArgumentException("Invalid map frame receipt");
            if(style.isPresent()) {
                var colors=style.orElseThrow();var values=summary.orElseThrow();
                boolean matching=kind==MapSamples.Kind.BIOME?colors instanceof MapRaster.BiomeStyle:colors instanceof MapRaster.ScalarStyle||kind==MapSamples.Kind.TERRAIN&&colors instanceof MapRaster.TerrainStyle;
                if(!matching||(long)values.present()+values.absent()!=grid.count()||values.reliefSamples()>0&&!(colors instanceof MapRaster.TerrainStyle))throw new IllegalArgumentException("Map colorization receipt differs from its source kind/grid");
            }
            if(scalarSource.isPresent()) {
                var scalar=scalarSource.orElseThrow();var expected=kind==MapSamples.Kind.TERRAIN?ScalarHistogram.Kind.TERRAIN_MAP:ScalarHistogram.Kind.NOISE_MAP;
                if(style.isEmpty()||kind==MapSamples.Kind.BIOME||scalar.kind()!=expected||!scalar.context().equals(context)||!scalar.horizontal().equals(grid)||(kind==MapSamples.Kind.TERRAIN)!=scalar.vertical().isPresent())throw new IllegalArgumentException("Map scalar identity differs from its completed source");
            }
        }
        /** Full-source histogram matching, independent of pan/zoom and gradient-only recoloring. */
        public boolean matches(ScalarHistogram histogram) {
            Objects.requireNonNull(histogram);if(scalarSource.isEmpty()||style.isEmpty())return false;
            var colors=style.orElseThrow() instanceof MapRaster.TerrainStyle terrain?terrain.colors():(MapRaster.ScalarStyle)style.orElseThrow();
            return histogram.matches(scalarSource.orElseThrow(),new ScalarHistogram.Range(colors.minimum(),colors.maximum()));
        }
        /** Automatic-range suggestions attest the exact original source without claiming current display colors. */
        public boolean matchesSource(ScalarHistogramWorker.Completion completion) {
            Objects.requireNonNull(completion);return !completion.closed()&&scalarSource.filter(completion.receipt()::equals).isPresent();
        }
        public boolean matches(ScalarHistogramWorker.Completion completion) {
            return matchesSource(completion)&&matches(completion.histogram());
        }
    }
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation journal;final GlTarget target;final GlMap map;final Info info;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation journal,GlTarget target,GlMap map,Info info) { this.device=device;this.journal=journal;this.target=target;this.map=map;this.info=info; }
    }
    private final Info info;private Shared shared;
    private GlMapFrame(Shared shared) { this.shared=shared;info=shared.info; }
    private static void check(BooleanSupplier obsolete) { if(obsolete.getAsBoolean())throw new CancellationException("Map frame request is obsolete"); }
    private static MapRaster validate(GlMapRenderer renderer,GlMap source,MapView view) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(source);Objects.requireNonNull(view);var device=renderer.device;device.live(renderer.allocation);
        if(source.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Map frame inputs must share the renderer device");
        var raster=source.raster();if(view.width()!=source.width()||view.height()!=source.height())throw new GlFailure(GlFailure.Code.STALE,"Map frame transform differs from its source dimensions");return raster;
    }
    /** Inputs are borrowed; only a successful draw/restoration publishes a fresh private target. */
    public static GlMapFrame render(GlMapRenderer renderer,long revision,GlMap source,MapView view,int width,int height,BooleanSupplier obsolete) {
        Objects.requireNonNull(obsolete);if(revision<1)throw new IllegalArgumentException("Invalid map frame revision");validate(renderer,source,view);check(obsolete);
        try(var prepared=prepare(renderer,revision,source,view,width,height)) { return prepared.render(obsolete); }
    }
    /** One-shot frame/target admission; the exact source remains independently owned until draw or closure. */
    static final class Prepared implements AutoCloseable {
        private final GlMapRenderer renderer;private final long revision;private final MapView view;private final int width,height;
        private GlMap owner;private GlDevice.Allocation journal;private GlTarget target;private boolean used,closed;
        private Prepared(GlMapRenderer renderer,long revision,GlMap owner,MapView view,int width,int height,GlDevice.Allocation journal,GlTarget target) { this.renderer=renderer;this.revision=revision;this.owner=owner;this.view=view;this.width=width;this.height=height;this.journal=journal;this.target=target; }
        GlMapFrame render(BooleanSupplier obsolete) {
            Objects.requireNonNull(obsolete);if(used||closed)throw new GlFailure(GlFailure.Code.STATE,"Map frame preparation is already used or closed");used=true;var raster=validate(renderer,owner,view);
            check(obsolete);target.initialize();check(obsolete);renderer.render(owner,target,view);check(obsolete);
            var identity=raster.samples().kind()==MapSamples.Kind.BIOME?Optional.<ScalarHistogram.Receipt>empty():Optional.of(ScalarHistogram.receipt(raster.samples()));
            var info=new Info(UUID.randomUUID(),revision,raster.samples().context(),raster.samples().kind(),raster.samples().grid(),view,width,height,Optional.of(raster.style()),raster.summary(),identity);var result=new GlMapFrame(new Shared(renderer.device,journal,target,owner,info));journal=null;target=null;owner=null;return result;
        }
        boolean ownsResources() { return journal!=null||target!=null||owner!=null; }
        @Override public void close() {
            closed=true;var device=renderer.device;Throwable failure=null;
            if(journal!=null)try { device.retire(journal);journal=null; }catch(RuntimeException|Error error) { if(journal.retired||journal.released)journal=null;failure=error; }
            if(target!=null)try { target.close();target=null; }catch(RuntimeException|Error error) { if(target.allocation.retired||target.allocation.released)target=null;if(failure==null)failure=error;else if(error!=failure)failure.addSuppressed(error); }
            if(owner!=null)try { owner.close();owner=null; }catch(RuntimeException|Error error) { if(owner.closed())owner=null;if(failure==null)failure=error;else if(error!=failure)failure.addSuppressed(error); }
            if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
        }
    }
    static Prepared prepare(GlMapRenderer renderer,long revision,GlMap source,MapView view,int width,int height) {
        if(revision<1)throw new IllegalArgumentException("Invalid map frame revision");var raster=validate(renderer,source,view);var device=renderer.device;GlTarget.validate(device,width,height);
        GlMap owner=null;GlDevice.Allocation journal=null;GlTarget target=null;
        try {
            owner=source.retain();journal=device.reserve(0,0,0,0,0,0,0,0,0,null);device.depend(journal,source.allocation);journal.retained=raster;
            target=GlTarget.admit(device,width,height);device.depend(journal,target.allocation);return new Prepared(renderer,revision,owner,view,width,height,journal,target);
        }catch(RuntimeException|Error failure) {
            if(journal!=null)try { device.retire(journal); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(target!=null)try { target.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(owner!=null)try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    private Shared live() { var value=shared;if(value==null||value.closing)throw new GlFailure(GlFailure.Code.STATE,"Map frame owner is closed or closing");value.device.live(value.journal);value.device.live(value.target.allocation);return value; }
    public Info info() { return info; }
    public boolean closed() { return shared==null; }
    /** Offscreen output uses OpenGL bottom-left UVs; a top-left image widget must flip texture V. */
    public int textureId() { return live().target.textureId(); }
    GlTarget readbackTarget() { return live().target; }
    GlDevice.Allocation readbackJournal() { return live().journal; }
    /** Borrowed exact native raster. Keep this frame owner live through inspection. */
    public MapRaster raster() { return live().map.raster(); }
    /** Context-thread capture; independently admitted CPU data survives frame/device closure. */
    public RenderInput<MapRaster> retainRasterInput() { return live().map.retainInput(); }
    /** Context-thread handoff only. Counting runs off-thread and retains no GL frame/device. */
    public long submitHistogram(ScalarHistogramWorker worker,ScalarHistogram.Limits limits) {
        var value=histogramSource(worker,limits);return worker.submitRaster(value.map.retainInput(),limits);
    }
    /** Empty range requests observed extrema for an explicit later recolor decision. */
    public long submitHistogram(ScalarHistogramWorker worker,Optional<ScalarHistogram.Range> range,ScalarHistogram.Limits limits) {
        Objects.requireNonNull(range);var value=histogramSource(worker,limits);return worker.submitRaster(value.map.retainInput(),range,limits);
    }
    private Shared histogramSource(ScalarHistogramWorker worker,ScalarHistogram.Limits limits) {
        Objects.requireNonNull(worker);Objects.requireNonNull(limits);var value=live();worker.validate();
        if(value.device.memory().orElse(null)!=worker.memory().orElse(null))throw new IllegalArgumentException("Map frame and histogram worker must share their memory coordinator");
        if(info.kind()==MapSamples.Kind.BIOME)throw new IllegalArgumentException("Biome palettes are categorical, not scalar distributions");return value;
    }
    public Optional<MapPick.Hit> hit(double x,double y) {
        var value=live();if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>=info.width()||y<0||y>=info.height())return Optional.empty();
        return MapPick.at(value.map.raster(),info.view(),x,y);
    }
    public GlMapFrame retain() { var value=live();if(value.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"Map frame owner count exceeds its bound");var result=new GlMapFrame(value);value.references++;return result; }
    @Override public void close() {
        var value=shared;if(value==null)return;if(!value.journal.released||!value.target.allocation.released||!value.map.allocation.released)value.device.current();
        if(value.references>1) { value.references--;shared=null;return; }value.closing=true;Throwable failure=null;
        try { value.device.retire(value.journal); }catch(RuntimeException|Error error) { failure=error; }
        try { value.target.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { value.map.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if((value.journal.retired||value.journal.released)&&(value.target.allocation.retired||value.target.allocation.released)&&value.map.closed()) { value.references=0;shared=null; }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
