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

/** Streams explicit native final RGB samples for emitted geometry; no light/AO factory or simulation. */
public final class NativeLightingInputs {
    private NativeLightingInputs() {}
    @FunctionalInterface public interface Sampler {
        float component(BlockState state,Biome biome,BlockPos position,WorldMesh mesh,int face,int vertex,int channel);
    }
    public record Provider(ClientAssetSnapshot.Receipt assets,String policy,Sampler sampler) {
        public Provider { Objects.requireNonNull(assets);Objects.requireNonNull(policy);Objects.requireNonNull(sampler);if(policy.isBlank()||policy.length()>256)throw new IllegalArgumentException("Native lighting provider requires a bounded policy"); }
    }
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<RenderInput<WorldLighting>> input,List<WorldProblem> problems) implements AutoCloseable {
        public Result { Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(input);problems=List.copyOf(problems);if(problems.isEmpty()!=input.isPresent())throw new IllegalArgumentException("Partial native lighting capture escaped failure"); }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
        @Override public void close() { if(input.isPresent())input.orElseThrow().close(); }
    }
    /** Caller retains the scene lease and freezes native session/resources throughout this synchronous worker call. */
    public static Result capture(NativeRenderContext context,WorldScene scene,Provider provider,WorldLighting.Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(scene);Objects.requireNonNull(provider);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);var snapshot=scene.snapshot();RenderInput<WorldLighting> owned=null;boolean transferred=false;
        Throwable escapingFailure=null;
        try {
            try {
                var input=WorldLighting.readOwned(scene,provider.policy(),new WorldLighting.Reader() {
                    private BlockState[] states;private Biome[] biomes;private BlockPos.MutableBlockPos position;private int lastSection=-1,lastFace=-1,cell;
                    @Override public void begin() {
                        context.check();var receipt=scene.layout().assets();
                        if(!snapshot.source().equals(context.source())||!provider.assets().archiveSha256().equals(receipt.archiveSha256())||!provider.assets().version().equals(receipt.version())||!provider.assets().expectedVersion().equals(receipt.expectedVersion()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native lighting belongs to another exact scene/assets context");
                        var extent=snapshot.extent();long height=(long)context.engine().getMaxHeight()-context.engine().getMinHeight();
                        if(extent.minY()!=context.engine().getMinHeight()||extent.height()!=height||height!=context.engine().getHeight())throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native lighting requires the complete configured world height");
                        if(extent.minX()<Integer.MIN_VALUE||extent.minZ()<Integer.MIN_VALUE||extent.maxX()-1>Integer.MAX_VALUE||extent.maxZ()-1>Integer.MAX_VALUE)throw NativeIdentities.failure(WorldProblem.Code.COORDINATE,"Native lighting positions exceed signed native coordinates");
                        position=new BlockPos.MutableBlockPos();var identities=new NativeIdentities(context.biomes(),snapshot.states().size(),1,16L*1024*1024,cancelled);states=new BlockState[snapshot.states().size()];biomes=new Biome[snapshot.biomes().size()];
                        for(int i=0;i<states.length;i++) {
                            check(cancelled);context.checkSession();var expected=snapshot.states().get(i);var handle=Block.stateById(expected.nativeStateId());
                            if(Block.getId(handle)!=expected.nativeStateId()||!identities.state(ModdedBlockState.of(handle,null)).equals(expected))throw NativeIdentities.failure(WorldProblem.Code.STATE,"Native lighting state/domain differs from the original captured registry");states[i]=handle;
                        }
                        for(int i=0;i<biomes.length;i++) {
                            check(cancelled);context.checkSession();var expected=snapshot.biomes().get(i);var handle=context.biomes().byId(expected.nativeBiomeId());var key=handle==null?null:context.biomes().getKey(handle);
                            if(key==null||context.biomes().getId(handle)!=expected.nativeBiomeId()||!key.toString().equals(expected.original())||!AssetId.parse(key.toString()).equals(expected.id()))throw NativeIdentities.failure(WorldProblem.Code.PALETTE,"Native lighting biome differs from the original captured registry");biomes[i]=handle;
                        }
                    }
                    @Override public int sections() { return scene.sections().size(); }
                    @Override public int components(int section) { return Math.multiplyExact(scene.sections().get(section).faces().size(),12); }
                    @Override public float component(int section,int face,int vertex,int channel) {
                        check(cancelled);context.checkSession();var mesh=scene.sections().get(section);
                        if(lastSection!=section||lastFace!=face) {
                            cell=mesh.cells().get(mesh.faces().get(face).cell()).sourceCell();var p=snapshot.extent().position(cell);position.set((int)p.x(),p.y(),(int)p.z());lastSection=section;lastFace=face;
                        }
                        return provider.sampler().component(states[snapshot.stateIndex(cell)],biomes[snapshot.biomeIndex(cell)],position,mesh,face,vertex,channel);
                    }
                    @Override public void complete() { context.check(); }
                },limits,memory,cancelled);owned=input;context.check();check(cancelled);
                var result=new Result(snapshot.source(),snapshot.id(),Optional.of(input),List.of());transferred=true;return result;
            }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,failure.problem()); }
            catch(RenderMemory.LimitException failure) { check(cancelled);return failed(snapshot,new WorldProblem(WorldProblem.Code.LIMIT,Optional.empty(),failure.getMessage())); }
            catch(CancellationException cancellation) { throw cancellation; }
            catch(RuntimeException failure) { check(cancelled);return failed(snapshot,new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native lighting producer failed: "+failure.getClass().getSimpleName())); }
            catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native lighting")); }
        }catch(RuntimeException|Error failure) { escapingFailure=failure;throw failure; }
        finally {
            try { if(!transferred&&owned!=null)owned.close(); }
            catch(RuntimeException|Error cleanup) { if(escapingFailure==null)throw cleanup;if(cleanup!=escapingFailure)escapingFailure.addSuppressed(cleanup); }
        }
    }
    private static Result failed(WorldSnapshot snapshot,WorldProblem problem) { return new Result(snapshot.source(),snapshot.id(),Optional.empty(),List.of(problem)); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native lighting capture cancelled"); }
}
