package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import dev.irisvisualizer.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Detaches an explicitly supplied native RGB-layer resolver; no tint factory, colormap or blending rule is guessed. */
public final class NativeTintInputs {
    private NativeTintInputs() {}
    @FunctionalInterface public interface Sampler { WorldTintInput.Layers colors(BlockState state,Biome biome,BlockPos position); }
    /** Host attests matching native resources/policy and exclusively freezes this resolver through capture. */
    public record Provider(ClientAssetSnapshot.Receipt assets,String policy,Sampler sampler) {
        public Provider { Objects.requireNonNull(assets);Objects.requireNonNull(policy);Objects.requireNonNull(sampler);if(policy.isBlank()||policy.length()>256)throw new IllegalArgumentException("Native tint provider requires a bounded policy"); }
    }
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldTintInput> input,List<WorldProblem> problems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(input);problems=List.copyOf(problems);
            if(problems.isEmpty()!=input.isPresent()||input.isPresent()&&(input.orElseThrow().requiresPlaceholder()||!input.orElseThrow().snapshot().id().equals(snapshotId)||!input.orElseThrow().snapshot().source().equals(source)))throw new IllegalArgumentException("Partial native tint inputs escaped failure");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,Provider provider,WorldTintInput.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(provider);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        try {
            check(cancelled);context.check();if(snapshot.requiresPlaceholder())return failed(snapshot,snapshot.problems());
            if(!snapshot.source().equals(context.source())||!provider.assets().archiveSha256().equals(assets.receipt().archiveSha256())||!provider.assets().version().equals(assets.receipt().version())||!provider.assets().expectedVersion().equals(assets.receipt().expectedVersion()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native tint provider belongs to another native/assets context");
            var extent=snapshot.extent();long height=(long)context.engine().getMaxHeight()-context.engine().getMinHeight();
            if(extent.minY()!=context.engine().getMinHeight()||extent.height()!=height||height!=context.engine().getHeight())throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native tint capture requires complete configured height");
            if(extent.minX()<Integer.MIN_VALUE||extent.minZ()<Integer.MIN_VALUE||extent.maxX()-1>Integer.MAX_VALUE||extent.maxZ()-1>Integer.MAX_VALUE)throw NativeIdentities.failure(WorldProblem.Code.COORDINATE,"Native tint positions exceed signed native coordinates");
            var identities=new NativeIdentities(context.biomes(),snapshot.states().size(),1,16L*1024*1024,cancelled);var states=new BlockState[snapshot.states().size()];
            for(int i=0;i<states.length;i++) { check(cancelled);context.checkSession();var expected=snapshot.states().get(i);var handle=Block.stateById(expected.nativeStateId());if(Block.getId(handle)!=expected.nativeStateId()||!identities.state(ModdedBlockState.of(handle,null)).equals(expected))throw NativeIdentities.failure(WorldProblem.Code.STATE,"Native tint state/domain differs from the captured registry");states[i]=handle; }
            var biomes=new Biome[snapshot.biomes().size()];
            for(int i=0;i<biomes.length;i++) { check(cancelled);context.checkSession();var expected=snapshot.biomes().get(i);var handle=context.biomes().byId(expected.nativeBiomeId());var key=handle==null?null:context.biomes().getKey(handle);if(key==null||context.biomes().getId(handle)!=expected.nativeBiomeId()||!key.toString().equals(expected.original())||!AssetId.parse(key.toString()).equals(expected.id()))throw NativeIdentities.failure(WorldProblem.Code.PALETTE,"Native tint biome ID/key differs from the captured registry");biomes[i]=handle; }
            var result=WorldTintInput.read(assets,snapshot,provider.policy(),new WorldTintInput.Reader() {
                private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
                @Override public WorldTintInput.Layers colors(int cell) {
                    check(cancelled);context.checkSession();long layer=(long)extent.width()*extent.depth();position.set((int)(extent.minX()+cell%extent.width()),(int)((long)extent.minY()+cell/layer),(int)(extent.minZ()+(cell/extent.width())%extent.depth()));
                    return provider.sampler().colors(states[snapshot.stateIndex(cell)],biomes[snapshot.biomeIndex(cell)],position);
                }
                @Override public void complete() { context.check(); }
            },limits,cancelled);
            context.check();check(cancelled);return result.requiresPlaceholder()?failed(snapshot,result.problems()):new Result(snapshot.source(),snapshot.id(),Optional.of(result),List.of());
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,List.of(failure.problem())); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native tint input capture failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native tint inputs"))); }
    }
    public static NativeWeightedInputs.Result weighted(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,Provider provider,long tick,double partialTick,NativeWeightedInputs.Limits limits,WorldTintInput.Limits tintLimits,BooleanSupplier cancelled) {
        return weighted(context,assets,snapshot,provider,Optional.empty(),tick,partialTick,limits,tintLimits,cancelled);
    }
    public static NativeWeightedInputs.Result weighted(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,Provider provider,RandomSource random,long tick,double partialTick,NativeWeightedInputs.Limits limits,WorldTintInput.Limits tintLimits,BooleanSupplier cancelled) {
        return weighted(context,assets,snapshot,provider,Optional.of(random),tick,partialTick,limits,tintLimits,cancelled);
    }
    private static NativeWeightedInputs.Result weighted(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,Provider provider,Optional<RandomSource> random,long tick,double partialTick,NativeWeightedInputs.Limits limits,WorldTintInput.Limits tintLimits,BooleanSupplier cancelled) {
        var bounded=new WorldTintInput.Limits(tintLimits.tables(),tintLimits.layers(),tintLimits.payloadBytes(),Math.min(tintLimits.workingBytes(),limits.scene().workingBytes()));var captured=capture(context,assets,snapshot,provider,bounded,cancelled);
        if(captured.requiresPlaceholder())return new NativeWeightedInputs.Result(snapshot.source(),snapshot.id(),Optional.empty(),Optional.empty(),captured.problems(),List.of());
        return NativeWeightedInputs.capture(context,assets,snapshot,random,tick,partialTick,limits,captured.input().orElseThrow(),false,cancelled);
    }
    private static Result failed(WorldSnapshot snapshot,List<WorldProblem> problems) { return new Result(snapshot.source(),snapshot.id(),Optional.empty(),problems); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native tint input capture cancelled"); }
}
