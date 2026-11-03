package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import dev.irisvisualizer.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Borrowed native producer boundary over already captured model choices; no default special/fluid producer. */
public final class NativeVisualInputs {
    private NativeVisualInputs() {}
    @FunctionalInterface public interface Sampler {
        WorldVisuals.Choice choice(int cell,BlockState state,Biome biome,BlockPos position,WorldVisuals.Choice original);
    }
    /** Host freezes and verifies this producer's native resources, selection, geometry and material policy. */
    public record Provider(ClientAssetSnapshot.Receipt assets,String policy,Sampler sampler) {
        public Provider { Objects.requireNonNull(assets);Objects.requireNonNull(policy);Objects.requireNonNull(sampler);if(policy.isBlank()||policy.length()>256)throw new IllegalArgumentException("Native visual provider requires a bounded policy"); }
    }
    public record Result(WorldSnapshot.Source source,UUID snapshotId,String policy,Optional<WorldRenderWorker.Request> request,Optional<WorldVisualEdits.Usage> usage,List<WorldProblem> problems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(policy);Objects.requireNonNull(request);Objects.requireNonNull(usage);problems=List.copyOf(problems);boolean success=problems.isEmpty();
            if(success!=(request.isPresent()&&usage.isPresent())||!success&&(request.isPresent()||usage.isPresent()))throw new IllegalArgumentException("Partial native visual request escaped failure");
            if(success&&(!request.orElseThrow().visuals().source().equals(source)||!request.orElseThrow().visuals().snapshotId().equals(snapshotId)))throw new IllegalArgumentException("Native visual capture identities differ");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result capture(NativeRenderContext context,WorldRenderWorker.Request original,Provider provider,BooleanSupplier cancelled) { return capture(context,original,provider,WorldVisualEdits.Limits.DEFAULT,cancelled); }
    public static Result capture(NativeRenderContext context,WorldRenderWorker.Request original,Provider provider,WorldVisualEdits.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(original);Objects.requireNonNull(provider);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);var input=original.visuals();var snapshot=input.snapshot();
        try {
            check(cancelled);context.check();var assets=input.assets();
            if(!snapshot.source().equals(context.source())||!provider.assets().archiveSha256().equals(assets.receipt().archiveSha256())||!provider.assets().version().equals(assets.receipt().version())||!provider.assets().expectedVersion().equals(assets.receipt().expectedVersion()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native visual producer belongs to another native/assets context");
            if(!original.visibility().problems().isEmpty())return failed(snapshot,provider.policy(),original.visibility().problems());
            var extent=snapshot.extent();long height=(long)context.engine().getMaxHeight()-context.engine().getMinHeight();
            if(extent.minY()!=context.engine().getMinHeight()||extent.height()!=height||height!=context.engine().getHeight())throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native visual capture requires complete configured height");
            if(extent.minX()<Integer.MIN_VALUE||extent.minZ()<Integer.MIN_VALUE||extent.maxX()-1>Integer.MAX_VALUE||extent.maxZ()-1>Integer.MAX_VALUE)throw NativeIdentities.failure(WorldProblem.Code.COORDINATE,"Native visual positions exceed signed native coordinates");
            long working=Math.min(Math.min(limits.workingBytes(),input.limits().workingBytes()),original.workingBytes()-original.visibility().bytes());
            if(snapshot.cellCount()>limits.cells()||input.inputBytes()+(long)snapshot.cellCount()*8>working)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native visual edit source/mask/assignment-copy peak exceeds its allowance");
            var identities=new NativeIdentities(context.biomes(),snapshot.states().size(),1,16L*1024*1024,cancelled);var states=new BlockState[snapshot.states().size()];
            for(int i=0;i<states.length;i++) { check(cancelled);context.checkSession();var expected=snapshot.states().get(i);var handle=Block.stateById(expected.nativeStateId());if(Block.getId(handle)!=expected.nativeStateId()||!identities.state(ModdedBlockState.of(handle,null)).equals(expected))throw NativeIdentities.failure(WorldProblem.Code.STATE,"Native visual state/domain differs from the captured registry");states[i]=handle; }
            var biomes=new Biome[snapshot.biomes().size()];
            for(int i=0;i<biomes.length;i++) { check(cancelled);context.checkSession();var expected=snapshot.biomes().get(i);var handle=context.biomes().byId(expected.nativeBiomeId());var key=handle==null?null:context.biomes().getKey(handle);if(key==null||context.biomes().getId(handle)!=expected.nativeBiomeId()||!key.toString().equals(expected.original())||!AssetId.parse(key.toString()).equals(expected.id()))throw NativeIdentities.failure(WorldProblem.Code.PALETTE,"Native visual biome ID/key differs from the captured registry");biomes[i]=handle; }
            var bounded=new WorldVisualEdits.Limits(limits.cells(),limits.changes(),limits.packets(),limits.addedPackedBytes(),working);
            var edited=WorldVisualEdits.capture(input,new WorldVisualEdits.Reader() {
                private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
                @Override public WorldVisuals.Choice choice(int cell,WorldVisuals.Choice prior) {
                    check(cancelled);context.checkSession();long layer=(long)extent.width()*extent.depth();position.set((int)(extent.minX()+cell%extent.width()),(int)((long)extent.minY()+cell/layer),(int)(extent.minZ()+(cell/extent.width())%extent.depth()));
                    return provider.sampler().choice(cell,states[snapshot.stateIndex(cell)],biomes[snapshot.biomeIndex(cell)],position,prior);
                }
                @Override public void complete() { context.check(); }
            },bounded,cancelled);
            if(edited.requiresPlaceholder())return failed(snapshot,provider.policy(),edited.problems());
            var request=new WorldRenderWorker.Request(edited.request().orElseThrow(),original.visibility(),original.sceneLimits(),original.sectionLimits(),original.workingBytes());
            context.check();check(cancelled);return new Result(snapshot.source(),snapshot.id(),provider.policy(),Optional.of(request),edited.usage(),List.of());
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,provider.policy(),List.of(failure.problem())); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,provider.policy(),List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native visual producer capture failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,provider.policy(),List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for native visual producer capture"))); }
    }
    /** The supplied quads are complete native output in the existing pre-offset block-local contract. */
    public static WorldVisuals.Choice geometry(ClientAssetSnapshot assets,WorldSnapshot snapshot,int cell,WorldVisuals.Choice original,NativeMeshSource.Kind kind,String policy,List<NativeGeometry.Quad> quads,boolean positionDependent,boolean additionalLayer,NativeGeometry.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(original);if(original.stateIndex()!=snapshot.stateIndex(cell))throw new IllegalArgumentException("Native geometry must preserve the original cell/state");
        var mesh=NativeGeometry.capture(assets,snapshot,cell,kind,policy,quads,positionDependent,limits,cancelled);
        return additionalLayer?WorldVisualEdits.layer(original,mesh,limits,cancelled):new WorldVisuals.NativeChoice(original.stateIndex(),mesh);
    }
    public static WorldVisuals.Choice geometry(ClientAssetSnapshot assets,WorldSnapshot snapshot,int cell,WorldVisuals.Choice original,NativeMeshSource.Kind kind,String policy,NativeGeometry.Reader reader,boolean positionDependent,boolean additionalLayer,NativeGeometry.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(original);if(original.stateIndex()!=snapshot.stateIndex(cell))throw new IllegalArgumentException("Native geometry must preserve the original cell/state");
        var mesh=NativeGeometry.read(assets,snapshot,cell,kind,policy,reader,positionDependent,limits,cancelled);
        return additionalLayer?WorldVisualEdits.layer(original,mesh,limits,cancelled):new WorldVisuals.NativeChoice(original.stateIndex(),mesh);
    }
    private static Result failed(WorldSnapshot snapshot,String policy,List<WorldProblem> problems) { return new Result(snapshot.source(),snapshot.id(),policy,Optional.empty(),Optional.empty(),problems); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native visual capture cancelled"); }
}
