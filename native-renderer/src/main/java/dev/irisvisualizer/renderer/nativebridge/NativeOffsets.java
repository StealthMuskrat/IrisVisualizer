package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import dev.irisvisualizer.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Detaches actual native BlockState.getOffset positions without reimplementing its seed/offset function. */
public final class NativeOffsets {
    private NativeOffsets() {}
    public static final String POLICY="Vanilla BlockState.getOffset(exact native block position); no replacement offset/random policy";
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldOffsets> offsets,List<WorldProblem> problems) {
        public Result { Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(offsets);problems=List.copyOf(problems);if(problems.isEmpty()!=offsets.isPresent()||offsets.isPresent()&&(offsets.orElseThrow().requiresPlaceholder()||!offsets.orElseThrow().snapshot().id().equals(snapshotId)||!offsets.orElseThrow().snapshot().source().equals(source)))throw new IllegalArgumentException("Partial native model offsets escaped failure"); }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result capture(NativeRenderContext context,WorldSnapshot snapshot,BooleanSupplier cancelled) { return capture(context,snapshot,WorldOffsets.Limits.DEFAULT,cancelled); }
    public static Result capture(NativeRenderContext context,WorldSnapshot snapshot,WorldOffsets.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(snapshot);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        try {
            check(cancelled);context.check();if(snapshot.requiresPlaceholder())return failed(snapshot,snapshot.problems());
            if(!snapshot.source().equals(context.source()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native model offsets belong to a different generation receipt");
            var extent=snapshot.extent();long height=(long)context.engine().getMaxHeight()-context.engine().getMinHeight();
            if(extent.minY()!=context.engine().getMinHeight()||extent.height()!=height||height!=context.engine().getHeight())throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native model offsets require complete configured height");
            if(extent.minX()<Integer.MIN_VALUE||extent.minZ()<Integer.MIN_VALUE||extent.maxX()-1>Integer.MAX_VALUE||extent.maxZ()-1>Integer.MAX_VALUE)throw NativeIdentities.failure(WorldProblem.Code.COORDINATE,"Native model offset positions exceed signed 32-bit coordinates");
            if(snapshot.indexBytes()>limits.workingBytes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native snapshot exceeds model offset working budget");
            var identities=new NativeIdentities(context.biomes(),snapshot.states().size(),1,16L*1024*1024,cancelled);var states=new BlockState[snapshot.states().size()];boolean any=false;
            for(int i=0;i<states.length;i++) { check(cancelled);context.checkSession();var expected=snapshot.states().get(i);var handle=Block.stateById(expected.nativeStateId());if(Block.getId(handle)!=expected.nativeStateId()||!identities.state(ModdedBlockState.of(handle,null)).equals(expected))throw NativeIdentities.failure(WorldProblem.Code.STATE,"Model offset source state/domain differs from the actual native registry");states[i]=handle;any|=handle.hasOffsetFunction(); }
            WorldOffsets offsets;
            if(!any)offsets=WorldOffsets.none(snapshot);
            else offsets=WorldOffsets.read(snapshot,POLICY,new WorldOffsets.Reader() {
                private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
                @Override public WorldOffsets.Offset offset(int cell) {
                    if((cell&4095)==0) { check(cancelled);context.checkSession(); }var state=states[snapshot.stateIndex(cell)];if(!state.hasOffsetFunction())return WorldOffsets.Offset.ZERO;
                    long layer=(long)extent.width()*extent.depth();position.set((int)(extent.minX()+cell%extent.width()),(int)((long)extent.minY()+cell/layer),(int)(extent.minZ()+(cell/extent.width())%extent.depth()));
                    var value=state.getOffset(position);double x=value.x(),y=value.y(),z=value.z();
                    if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>limits.componentBlocks())throw new WorldCapture.ReadFailure(new WorldProblem(WorldProblem.Code.COORDINATE,Optional.of(extent.position(cell)),"Native model translation exceeds its finite local-coordinate bound"));
                    return new WorldOffsets.Offset(x,y,z);
                }
                @Override public void complete() { context.check(); }
            },limits,cancelled);
            context.check();check(cancelled);return offsets.requiresPlaceholder()?failed(snapshot,offsets.problems()):new Result(snapshot.source(),snapshot.id(),Optional.of(offsets),List.of());
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,List.of(failure.problem())); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native model offset capture failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native model offsets"))); }
    }
    private static Result failed(WorldSnapshot snapshot,List<WorldProblem> problems) { return new Result(snapshot.source(),snapshot.id(),Optional.empty(),problems); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native model offset capture cancelled"); }
}
