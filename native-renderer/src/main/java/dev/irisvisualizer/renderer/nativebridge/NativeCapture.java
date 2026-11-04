package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.iris.generation.noise.IrisExpression;
import art.arcane.iris.generation.noise.IrisGenerator;
import art.arcane.iris.generation.noise.IrisNoiseGenerator;
import dev.irisvisualizer.renderer.*;
import net.minecraft.util.RandomSource;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Admitted synchronous native capture to detached owned inputs. Caller freezes/owns the native session. */
public final class NativeCapture {
    private NativeCapture() {}
    private static final long WORLD_BYTES=512L*1024*1024;
    public enum Kind { VOLUME, MODELS, WEIGHTED, VISUAL_EDIT, TERRAIN, BIOMES, NOISE_LAYER, GENERATOR, EXPRESSION, GENERATOR_VOLUME, EXPRESSION_VOLUME, FLUIDS, FLUID_SCENE }
    public record WorldLimits(NativeWorldCapture.Limits volume,NativeWeightedInputs.Limits visuals) {
        public static final WorldLimits DEFAULT=new WorldLimits(NativeWorldCapture.Limits.DEFAULT,NativeWeightedInputs.Limits.DEFAULT);
        public WorldLimits { Objects.requireNonNull(volume);Objects.requireNonNull(visuals); }
    }
    public record WorldOptions(Optional<NativeTintInputs.Provider> tint,WorldTintInput.Limits tintLimits,Optional<ClientColormaps> colormaps) {
        public static final WorldOptions NONE=new WorldOptions(Optional.empty(),WorldTintInput.Limits.DEFAULT);
        public WorldOptions(Optional<NativeTintInputs.Provider> tint,WorldTintInput.Limits tintLimits) { this(tint,tintLimits,Optional.empty()); }
        public WorldOptions {
            Objects.requireNonNull(tint);Objects.requireNonNull(tintLimits);Objects.requireNonNull(colormaps);
            if(tint.isPresent()&&colormaps.isPresent()||colormaps.isPresent()&&colormaps.orElseThrow().requiresPlaceholder())throw new IllegalArgumentException("Choose one successful explicit or vanilla colormap tint source");
        }
        /** Explicit radius-zero profile; does not claim neighboring-biome blending. */
        public static WorldOptions vanillaUnblended(ClientColormaps maps) { return new WorldOptions(Optional.empty(),WorldTintInput.Limits.DEFAULT,Optional.of(maps)); }
    }
    /** Successful input owns only detached data and admission; no engine, reader, RNG or provider escapes. */
    public record Result<T>(Kind kind,WorldSnapshot.Source source,Optional<RenderInput<T>> input,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) implements AutoCloseable {
        public Result {
            Objects.requireNonNull(kind);Objects.requireNonNull(source);Objects.requireNonNull(input);problems=List.copyOf(problems);assetProblems=List.copyOf(assetProblems);
            if((problems.isEmpty()&&assetProblems.isEmpty())!=input.isPresent())throw new IllegalArgumentException("Partial admitted native capture escaped failure");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty()||!assetProblems.isEmpty(); }
        @Override public void close() { if(input.isPresent())input.orElseThrow().close(); }
    }
    private record Captured<T>(Optional<T> value,long bytes,List<WorldProblem> problems,List<ModelPlan.Problem> assets) {}
    @FunctionalInterface private interface Capture<T> { Captured<T> run(); }
    @FunctionalInterface private interface Samples { MapSamples capture(); }
    private static <T> Captured<T> success(T value,long bytes) { return new Captured<>(Optional.of(value),bytes,List.of(),List.of()); }
    private static <T> Captured<T> failure(List<WorldProblem> problems,List<ModelPlan.Problem> assets) { return new Captured<>(Optional.empty(),0,problems,assets); }
    private static <T> Result<T> failed(Kind kind,WorldSnapshot.Source source,List<WorldProblem> problems,List<ModelPlan.Problem> assets) { return new Result<>(kind,source,Optional.empty(),problems,assets); }
    private static <T> Result<T> capture(Kind kind,NativeRenderContext context,RenderMemory memory,long reservation,Capture<T> capture,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(capture);Objects.requireNonNull(cancelled);var source=context.source();RenderMemory.Hold hold=RenderMemory.Hold.none();RenderInput<T> input=null;boolean transferred=false;
        try {
            check(cancelled);if(reservation<0||reservation>WORLD_BYTES)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native capture aggregate working reservation exceeds 512 MiB");
            // Rejection precedes any session/native sampler call or output array allocation.
            if(memory!=null)hold=memory.hold("Native "+kind.name().toLowerCase(Locale.ROOT)+" capture",reservation);
            context.check();var result=capture.run();context.check();check(cancelled);
            if(result.value().isEmpty())return failed(kind,source,result.problems(),result.assets());
            if(result.bytes()<0||result.bytes()>reservation)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Detached native output exceeds its admitted capture peak");
            hold.shrinkTo(result.bytes());input=RenderInput.own(result.value().orElseThrow(),hold);
            var complete=new Result<>(kind,source,Optional.of(input),List.of(),List.of());transferred=true;return complete;
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(kind,source,List.of(failure.problem()),List.of()); }
        catch(RenderMemory.LimitException failure) { check(cancelled);return failed(kind,source,List.of(new WorldProblem(WorldProblem.Code.LIMIT,Optional.empty(),failure.getMessage())),List.of()); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(kind,source,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Admitted native capture failed: "+failure.getClass().getSimpleName())),List.of()); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(kind,source,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for native capture; no partial input published")),List.of()); }
        finally { if(!transferred&&input!=null)input.close();hold.close(); }
    }
    /** Reads caller-frozen completed chunks; generation/chunk storage is external to this reservation. */
    public static Result<WorldSnapshot> volume(NativeRenderContext context,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,NativeWorldCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(limits);
        return capture(Kind.VOLUME,context,memory,limits.capture().workingBytes(),()->{
            var result=NativeWorldCapture.capture(context,extent,chunks,limits,cancelled);
            return result.requiresPlaceholder()?failure(result.problems(),List.of()):success(result.snapshot().orElseThrow(),result.snapshot().orElseThrow().indexBytes());
        },cancelled);
    }
    private static WorldTintInput tint(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,WorldOptions options,long working,BooleanSupplier cancelled) {
        if(options.tint().isEmpty()&&options.colormaps().isEmpty())return WorldTintInput.none(snapshot);
        var t=options.tintLimits();var limits=new WorldTintInput.Limits(t.tables(),t.layers(),t.payloadBytes(),Math.min(working,t.workingBytes()));
        var result=options.colormaps().isPresent()?NativeColormapTints.capture(context,assets,snapshot,options.colormaps().orElseThrow(),limits,cancelled):NativeTintInputs.capture(context,assets,snapshot,options.tint().orElseThrow(),limits,cancelled);
        if(result.requiresPlaceholder())throw new WorldCapture.ReadFailure(result.problems().getFirst());return result.input().orElseThrow();
    }
    private static void clock(long tick,double partialTick) { if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid native capture animation clock"); }
    private static void client(ClientAssetSnapshot assets) {
        if(!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw NativeIdentities.failure(WorldProblem.Code.VERSION,"Native capture requires the selected pinned client profile");
    }
    private static void client(ClientAssetSnapshot assets,WorldOptions options) {
        client(assets);if(options.colormaps().isPresent()&&!options.colormaps().orElseThrow().matchesAssets(assets.receipt()))throw NativeIdentities.failure(WorldProblem.Code.VERSION,"Vanilla tint colormaps belong to another client archive");
    }
    private static long colorBytes(WorldOptions options) { return options.colormaps().map(ClientColormaps::bytes).orElse(0L); }
    private static long worldPeak(ClientAssetSnapshot assets,WorldLimits limits,WorldOptions options) { return Math.max(assets.receipt().capturedAssetBytes()+limits.volume().capture().workingBytes()+colorBytes(options),limits.visuals().scene().workingBytes()); }
    /** Borrowed source tables remain live through scene capture and consume its existing ceiling. */
    private static NativeSceneInputs.Limits sceneLimits(NativeSceneInputs.Limits source,WorldOptions options) {
        long working=source.workingBytes()-colorBytes(options);if(working<0)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Colormap tables exceed native scene working allowance");
        return new NativeSceneInputs.Limits(source.models(),source.visibility(),source.offsets(),source.scene(),source.section(),working);
    }
    private static void catalogOwner(RenderInput<ClientAssetSnapshot> assets,RenderMemory memory) {
        if(assets.admission().tracked()&&(memory==null||!assets.admission().belongsTo(memory)))throw new IllegalArgumentException("Native capture requires the loaded catalog's shared coordinator");
    }
    /** Consumes a fresh loaded catalog owner on every outcome; retain another owner for later regeneration. */
    public static Result<WorldRenderWorker.ModelRequest> models(NativeRenderContext context,RenderInput<ClientAssetSnapshot> assets,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,long tick,double partialTick,WorldLimits limits,WorldOptions options,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);
        try(var source=assets.retain()) { catalogOwner(source,memory);return models(context,source.value(),extent,chunks,tick,partialTick,limits,options,memory,cancelled); }
        finally { assets.close(); }
    }
    /** Same consuming catalog handoff for native weighted model selection; RNG policy remains caller-owned. */
    public static Result<WorldRenderWorker.Request> weighted(NativeRenderContext context,RenderInput<ClientAssetSnapshot> assets,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,RandomSource random,long tick,double partialTick,WorldLimits limits,WorldOptions options,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);
        try(var source=assets.retain()) { catalogOwner(source,memory);return weighted(context,source.value(),extent,chunks,random,tick,partialTick,limits,options,memory,cancelled); }
        finally { assets.close(); }
    }
    /** Unit-weight inspection remains deferred to WorldRenderWorker; missing native producers stay guarded. */
    public static Result<WorldRenderWorker.ModelRequest> models(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,long tick,double partialTick,WorldLimits limits,WorldOptions options,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(limits);Objects.requireNonNull(options);clock(tick,partialTick);
        return capture(Kind.MODELS,context,memory,worldPeak(assets,limits,options),()->{
            client(assets,options);var volume=NativeWorldCapture.capture(context,extent,chunks,limits.volume(),cancelled);if(volume.requiresPlaceholder())return failure(volume.problems(),List.of());
            var snapshot=volume.snapshot().orElseThrow();var scene=limits.visuals().scene();var colors=tint(context,assets,snapshot,options,scene.workingBytes(),cancelled);
            var result=NativeSceneInputs.capture(context,assets,snapshot,tick,partialTick,sceneLimits(scene,options),colors,cancelled);
            return result.requiresPlaceholder()?failure(result.problems(),List.of()):success(result.request().orElseThrow(),result.request().orElseThrow().inputBytes());
        },cancelled);
    }
    /** The native renderer-matching RNG remains explicitly supplied and exclusively owned by the caller. */
    public static Result<WorldRenderWorker.Request> weighted(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,RandomSource random,long tick,double partialTick,WorldLimits limits,WorldOptions options,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(random);Objects.requireNonNull(limits);Objects.requireNonNull(options);clock(tick,partialTick);
        return capture(Kind.WEIGHTED,context,memory,worldPeak(assets,limits,options),()->{
            client(assets,options);var volume=NativeWorldCapture.capture(context,extent,chunks,limits.volume(),cancelled);if(volume.requiresPlaceholder())return failure(volume.problems(),List.of());
            var snapshot=volume.snapshot().orElseThrow();var colors=tint(context,assets,snapshot,options,limits.visuals().scene().workingBytes(),cancelled);
            var original=limits.visuals();var bounded=new NativeWeightedInputs.Limits(sceneLimits(original.scene(),options),original.parts(),original.alternatives(),original.selectionVisits(),original.weightUnits(),original.nativeQueries());
            var result=NativeWeightedInputs.capture(context,assets,snapshot,random,tick,partialTick,bounded,colors,cancelled);
            return result.requiresPlaceholder()?failure(result.problems(),result.assetProblems()):success(result.request().orElseThrow(),result.request().orElseThrow().inputBytes());
        },cancelled);
    }
    /** Consumes a loaded catalog; base choices and fluid layers are completed under one admitted phase. */
    public static Result<WorldRenderWorker.Request> fluidScene(NativeRenderContext context,RenderInput<ClientAssetSnapshot> assets,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,RandomSource random,long tick,double partialTick,WorldLimits limits,WorldOptions options,NativeFluidInputs.Limits fluids,RenderMemory memory,BooleanSupplier cancelled) {
        return fluidScene(context,assets,extent,chunks,random,tick,partialTick,limits,options,fluids,Optional.empty(),memory,cancelled);
    }
    /** Additional detached neighbors stay borrowed through capture and are not retained by output. */
    public static Result<WorldRenderWorker.Request> fluidScene(NativeRenderContext context,RenderInput<ClientAssetSnapshot> assets,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,RandomSource random,long tick,double partialTick,WorldLimits limits,WorldOptions options,NativeFluidInputs.Limits fluids,Optional<WorldSnapshot> neighbors,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(neighbors);
        try(var source=assets.retain()) { catalogOwner(source,memory);return fluidScene(context,source.value(),extent,chunks,random,tick,partialTick,limits,options,fluids,neighbors,memory,cancelled); }
        finally { assets.close(); }
    }
    /** No detached base-only fluid request is published, even if later sampling or admission fails. */
    public static Result<WorldRenderWorker.Request> fluidScene(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,RandomSource random,long tick,double partialTick,WorldLimits limits,WorldOptions options,NativeFluidInputs.Limits fluids,RenderMemory memory,BooleanSupplier cancelled) {
        return fluidScene(context,assets,extent,chunks,random,tick,partialTick,limits,options,fluids,Optional.empty(),memory,cancelled);
    }
    /** Optional exact-source/full-height halo is charged across volume, tint, selection and fluid phases. */
    public static Result<WorldRenderWorker.Request> fluidScene(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot.Extent extent,List<NativeWorldCapture.Chunk> chunks,RandomSource random,long tick,double partialTick,WorldLimits limits,WorldOptions options,NativeFluidInputs.Limits fluids,Optional<WorldSnapshot> neighbors,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(random);Objects.requireNonNull(limits);Objects.requireNonNull(options);Objects.requireNonNull(fluids);Objects.requireNonNull(neighbors);clock(tick,partialTick);
        long extra=neighbors.map(WorldSnapshot::indexBytes).orElse(0L),peak=Math.max(worldPeak(assets,limits,options),assets.receipt().capturedAssetBytes()+limits.volume().capture().workingBytes()+colorBytes(options)+extra);
        return capture(Kind.FLUID_SCENE,context,memory,peak,()->{
            client(assets,options);
            if(neighbors.isPresent()) {
                var halo=neighbors.orElseThrow();if(halo.requiresPlaceholder()||!halo.source().equals(context.source())||halo.extent().minY()!=extent.minY()||halo.extent().height()!=extent.height())throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Fluid scene neighbors require the same native source and full height");
            }
            var original=limits.visuals();var scene=sceneLimits(original.scene(),options);long available=scene.workingBytes()-extra;
            if(available<0)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Fluid scene neighbor indices exceed the phase allowance");
            var reserved=new NativeSceneInputs.Limits(scene.models(),scene.visibility(),scene.offsets(),scene.scene(),scene.section(),available);
            var volume=NativeWorldCapture.capture(context,extent,chunks,limits.volume(),cancelled);if(volume.requiresPlaceholder())return failure(volume.problems(),List.of());
            var snapshot=volume.snapshot().orElseThrow();var colors=tint(context,assets,snapshot,options,original.scene().workingBytes()-extra,cancelled);
            var bounded=new NativeWeightedInputs.Limits(reserved,original.parts(),original.alternatives(),original.selectionVisits(),original.weightUnits(),original.nativeQueries());
            var base=NativeWeightedInputs.capture(context,assets,snapshot,random,tick,partialTick,bounded,colors,true,cancelled);if(base.requiresPlaceholder())return failure(base.problems(),base.assetProblems());
            var result=NativeFluidInputs.captureReservedNeighbors(context,base.request().orElseThrow(),neighbors,fluids,cancelled);
            return result.requiresPlaceholder()?failure(result.problems(),List.of()):success(result.request().orElseThrow(),result.request().orElseThrow().inputBytes());
        },cancelled);
    }
    /** Consumes a ready choice request; strict optional neighbor data stays borrowed through capture. */
    public static Result<WorldRenderWorker.Request> fluids(NativeRenderContext context,RenderInput<WorldRenderWorker.Request> original,Optional<WorldSnapshot> neighbors,NativeFluidInputs.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(original);Objects.requireNonNull(neighbors);Objects.requireNonNull(limits);
        try(var source=original.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Fluid capture requires the same source coordinator");
            var request=source.value();return capture(Kind.FLUIDS,context,memory,Math.min(limits.edits().workingBytes(),request.workingBytes()),()->{
                var result=NativeFluidInputs.capture(context,request,neighbors,limits,cancelled);
                return result.requiresPlaceholder()?failure(result.problems(),List.of()):success(result.request().orElseThrow(),result.request().orElseThrow().inputBytes());
            },cancelled);
        }finally { original.close(); }
    }
    /** Consumes an owned ready request on every outcome; explicit geometry producers remain caller supplied. */
    public static Result<WorldRenderWorker.Request> edit(NativeRenderContext context,RenderInput<WorldRenderWorker.Request> original,NativeVisualInputs.Provider provider,WorldVisualEdits.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(original);Objects.requireNonNull(provider);Objects.requireNonNull(limits);
        try(var source=original.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Native visual edits require the same source coordinator");
            var request=source.value();
            return capture(Kind.VISUAL_EDIT,context,memory,Math.min(limits.workingBytes(),request.workingBytes()),()->{
                var result=NativeVisualInputs.capture(context,request,provider,limits,cancelled);
                return result.requiresPlaceholder()?failure(result.problems(),List.of()):success(result.request().orElseThrow(),result.request().orElseThrow().inputBytes());
            },cancelled);
        }finally { original.close(); }
    }
    private static Result<MapRasterWorker.Request> map(Kind kind,NativeRenderContext context,MapRaster.Style style,MapRaster.Limits rasterLimits,MapCapture.Limits limits,RenderMemory memory,Samples sampler,BooleanSupplier cancelled) {
        Objects.requireNonNull(style);Objects.requireNonNull(rasterLimits);Objects.requireNonNull(limits);
        return capture(kind,context,memory,limits.workingBytes(),()->{
            var samples=sampler.capture();if(samples.requiresPlaceholder())return failure(samples.problems(),List.of());
            return success(new MapRasterWorker.Request(samples,style,rasterLimits),samples.payloadBytes());
        },cancelled);
    }
    public static Result<MapRasterWorker.Request> terrain(NativeRenderContext context,MapSamples.Context source,MapSamples.Grid grid,boolean includeFluids,MapRaster.Style style,MapRaster.Limits rasterLimits,MapCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        return map(Kind.TERRAIN,context,style,rasterLimits,limits,memory,()->NativeMapSampler.terrain(context,source,grid,includeFluids,limits,cancelled),cancelled);
    }
    public static Result<MapRasterWorker.Request> biomes(NativeRenderContext context,MapSamples.Context source,MapSamples.Grid grid,MapRaster.Style style,MapRaster.Limits rasterLimits,MapCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        return map(Kind.BIOMES,context,style,rasterLimits,limits,memory,()->NativeMapSampler.biomes(context,source,grid,limits,cancelled),cancelled);
    }
    /** Display colors are keyed by exact detached native identity, then ordered by the captured palette. */
    public static Result<MapRasterWorker.Request> biomes(NativeRenderContext context,MapSamples.Context source,MapSamples.Grid grid,Map<WorldSnapshot.Biome,Integer> colors,MapRaster.Limits rasterLimits,MapCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(colors);Objects.requireNonNull(rasterLimits);Objects.requireNonNull(limits);
        if(colors.size()>8192)throw new IllegalArgumentException("Native biome display color table exceeds its bound");var frozen=Map.copyOf(colors);
        return capture(Kind.BIOMES,context,memory,limits.workingBytes(),()->{
            var samples=NativeMapSampler.biomes(context,source,grid,limits,cancelled);if(samples.requiresPlaceholder())return failure(samples.problems(),List.of());
            var rgba=new ArrayList<Integer>(samples.biomePalette().size());
            for(var biome:samples.biomePalette()) {
                check(cancelled);var color=frozen.get(biome);
                if(color==null)throw NativeIdentities.failure(WorldProblem.Code.PALETTE,"Display color missing for captured native biome ID "+biome.nativeBiomeId());rgba.add(color);
            }
            return success(new MapRasterWorker.Request(samples,new MapRaster.BiomeStyle(rgba),rasterLimits),samples.payloadBytes());
        },cancelled);
    }
    public static Result<MapRasterWorker.Request> noiseLayer(NativeRenderContext context,IrisNoiseGenerator layer,MapSamples.Context source,MapSamples.Grid grid,MapRaster.Style style,MapRaster.Limits rasterLimits,MapCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(layer);return map(Kind.NOISE_LAYER,context,style,rasterLimits,limits,memory,()->NativeMapSampler.noiseLayer(context,layer,source,grid,limits,cancelled),cancelled);
    }
    public static Result<MapRasterWorker.Request> generator(NativeRenderContext context,IrisGenerator generator,MapSamples.Context source,MapSamples.Grid grid,MapRaster.Style style,MapRaster.Limits rasterLimits,MapCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(generator);return map(Kind.GENERATOR,context,style,rasterLimits,limits,memory,()->NativeMapSampler.generator(context,generator,source,grid,limits,cancelled),cancelled);
    }
    public static Result<MapRasterWorker.Request> expression(NativeRenderContext context,IrisExpression expression,MapSamples.Context source,MapSamples.Grid grid,MapRaster.Style style,MapRaster.Limits rasterLimits,MapCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(expression);return map(Kind.EXPRESSION,context,style,rasterLimits,limits,memory,()->NativeMapSampler.expression(context,expression,source,grid,limits,cancelled),cancelled);
    }
    public static Result<NoiseVolume> generatorVolume(NativeRenderContext context,IrisGenerator generator,MapSamples.Context source,NoiseVolume.Grid grid,NoiseCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(generator);Objects.requireNonNull(limits);
        return capture(Kind.GENERATOR_VOLUME,context,memory,limits.workingBytes(),()->{
            var volume=NativeNoiseSampler.generator(context,generator,source,grid,limits,cancelled);return volume.requiresPlaceholder()?failure(volume.problems(),List.of()):success(volume,volume.payloadBytes());
        },cancelled);
    }
    public static Result<NoiseVolume> expressionVolume(NativeRenderContext context,IrisExpression expression,MapSamples.Context source,NoiseVolume.Grid grid,NoiseCapture.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(expression);Objects.requireNonNull(limits);
        return capture(Kind.EXPRESSION_VOLUME,context,memory,limits.workingBytes(),()->{
            var volume=NativeNoiseSampler.expression(context,expression,source,grid,limits,cancelled);return volume.requiresPlaceholder()?failure(volume.problems(),List.of()):success(volume,volume.payloadBytes());
        },cancelled);
    }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Admitted native renderer capture cancelled"); }
}
