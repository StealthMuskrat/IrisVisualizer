package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import dev.irisvisualizer.renderer.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Native render-shape decisions for guarded deterministic JSON visuals; no client code is executed. */
public final class NativeModelInputs {
    private NativeModelInputs() {}
    /** Detaches native facts before any JSON work; safe to queue after releasing the native session. */
    public record Capture(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldModelInputs.Request> inputs,List<WorldProblem> problems) {
        public Capture { Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(inputs);problems=List.copyOf(problems);if(problems.isEmpty()!=inputs.isPresent()||inputs.isPresent()&&(!inputs.orElseThrow().snapshot().id().equals(snapshotId)||!inputs.orElseThrow().snapshot().source().equals(source)))throw new IllegalArgumentException("Partial native decision capture escaped failure"); }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static WorldModelInputs.Result prepare(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,BooleanSupplier cancelled) { return prepare(context,assets,snapshot,tick,partialTick,WorldModelInputs.Limits.DEFAULT,cancelled); }
    public static WorldModelInputs.Result prepare(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,WorldModelInputs.Limits limits,BooleanSupplier cancelled) {
        var captured=capture(context,assets,snapshot,tick,partialTick,limits,cancelled);
        if(captured.requiresPlaceholder())return new WorldModelInputs.Result(snapshot.source(),snapshot.id(),Optional.empty(),Optional.empty(),captured.problems(),List.of());
        var result=WorldModelInputs.prepare(captured.inputs().orElseThrow(),cancelled);
        try { context.check();check(cancelled);return result; }
        catch(WorldCapture.ReadFailure failure) { check(cancelled);return new WorldModelInputs.Result(snapshot.source(),snapshot.id(),Optional.empty(),Optional.empty(),List.of(failure.problem()),List.of()); }
    }
    public static Capture capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,BooleanSupplier cancelled) { return capture(context,assets,snapshot,tick,partialTick,WorldModelInputs.Limits.DEFAULT,cancelled); }
    public static Capture capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,WorldModelInputs.Limits limits,BooleanSupplier cancelled) { return capture(context,assets,snapshot,tick,partialTick,limits,null,cancelled); }
    public static Capture capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,WorldModelInputs.Limits limits,WorldOffsets offsets,BooleanSupplier cancelled) { return capture(context,assets,snapshot,tick,partialTick,limits,offsets,WorldTintInput.none(snapshot),cancelled); }
    public static Capture capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,WorldModelInputs.Limits limits,WorldOffsets offsets,WorldTintInput tints,BooleanSupplier cancelled) {
        return capture(context,assets,snapshot,tick,partialTick,limits,offsets,tints,false,cancelled);
    }
    /** Internal base-only phase; the combined fluid route completes every fluid decision before publication. */
    static Capture capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,WorldModelInputs.Limits limits,WorldOffsets offsets,WorldTintInput tints,boolean fluidBases,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid native visual clock");
        try {
            check(cancelled);context.check();if(snapshot.requiresPlaceholder())return failed(snapshot,snapshot.problems());
            if(offsets!=null&&(offsets.snapshot()!=snapshot||offsets.requiresPlaceholder()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native model offsets must use the exact successful capture");
            if(tints.snapshot()!=snapshot||tints.requiresPlaceholder()||!tints.matchesAssets(assets.receipt()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native model tints require the exact snapshot/assets");
            if(!snapshot.source().equals(context.source()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native model decisions belong to a different generation receipt");
            if(!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw NativeIdentities.failure(WorldProblem.Code.VERSION,"Native decisions require the selected pinned client profile");
            var engine=context.engine();if(snapshot.extent().minY()!=engine.getMinHeight()||(long)snapshot.extent().height()!=((long)engine.getMaxHeight()-engine.getMinHeight()))throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native world visual decisions must preserve complete configured height");
            if(snapshot.states().size()>limits.visuals().choices()||assets.receipt().capturedAssetBytes()+snapshot.indexBytes()+(offsets==null?0:offsets.bytes())+tints.bytes()+(long)snapshot.cellCount()*8>limits.visuals().workingBytes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native visual state/assignment capture budget reached");
            var identities=new NativeIdentities(context.biomes(),snapshot.states().size(),1,16L*1024*1024,cancelled);var decisions=new ArrayList<WorldModelInputs.Decision>(snapshot.states().size());int problems=0;
            for(var state:snapshot.states()) {
                check(cancelled);context.checkSession();var handle=Block.stateById(state.nativeStateId());
                if(Block.getId(handle)!=state.nativeStateId()||!identities.state(ModdedBlockState.of(handle,null)).equals(state))throw NativeIdentities.failure(WorldProblem.Code.STATE,"Captured state/properties do not exactly match the native visual registry");
                if(!handle.getFluidState().isEmpty()&&!(fluidBases&&NativeFluidInputs.modelBase(handle,state.block())))decisions.add(unsupported(state,"/native/fluid","Native fluid/waterlogged visual producer is not supplied"));
                else if(handle.hasBlockEntity()&&!(handle.getRenderShape()==RenderShape.MODEL&&VanillaModelPolicy.modelOnlyBlockEntity(BlockModels.PROFILE,state.block())))decisions.add(unsupported(state,"/native/block_entity","Native block-entity/special visual producer is not supplied"));
                else if(handle.hasOffsetFunction()&&(offsets==null||offsets.mode()!=WorldOffsets.Mode.NATIVE))decisions.add(unsupported(state,"/native/offset","Native position-dependent model offset producer is not supplied"));
                else if(handle.getRenderShape()==RenderShape.INVISIBLE)decisions.add(WorldModelInputs.Decision.empty());
                else if(handle.getRenderShape()==RenderShape.MODEL)decisions.add(WorldModelInputs.Decision.model());
                else decisions.add(unsupported(state,"/native/render_shape","Native special render shape is not supported by JSON model preparation"));
                problems+=decisions.getLast().problems().size();if(problems>limits.visuals().problemReferences())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native visual decision diagnostic-reference budget reached");
            }
            context.check();var frozen=WorldModelInputs.Request.capture(assets,snapshot,decisions,tick,partialTick,limits,offsets==null?WorldOffsets.none(snapshot):offsets,tints,cancelled);context.check();check(cancelled);return new Capture(snapshot.source(),snapshot.id(),Optional.of(frozen),List.of());
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,List.of(failure.problem())); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native visual decision capture failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to prepare native visual decisions; no partial request was published"))); }
    }
    private static WorldModelInputs.Decision unsupported(RenderBlockState state,String pointer,String reason) { return WorldModelInputs.Decision.placeholder(List.of(new ModelPlan.Problem(ModelPlan.Code.UNSUPPORTED_GEOMETRY,ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),pointer,reason))); }
    private static Capture failed(WorldSnapshot snapshot,List<WorldProblem> problems) { return new Capture(snapshot.source(),snapshot.id(),Optional.empty(),problems); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native visual decision capture cancelled"); }
}
