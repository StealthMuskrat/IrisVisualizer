package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import dev.irisvisualizer.renderer.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Vanilla block-face visibility from exact native states; no opacity/shape algorithm is replaced. */
public final class NativeVisibility {
    private NativeVisibility() {}
    public static final String POLICY="Vanilla Block.shouldRenderFace(state,neighbor,direction); in-volume neighbors; boundaries retained; no fluid culling";
    public record Limits(WorldVisibility.Limits visibility,int cachedPairs,long nativeQueries,long pairProbes) {
        public static final Limits DEFAULT=new Limits(WorldVisibility.Limits.DEFAULT,65536,1048576,268435456);
        public Limits { Objects.requireNonNull(visibility);if(cachedPairs<0||cachedPairs>65536||nativeQueries<0||nativeQueries>1048576||pairProbes<0||pairProbes>268435456)throw new IllegalArgumentException("Native face-query limits exceed renderer bounds"); }
    }
    public record Usage(long cells,long inVolumeDirections,long boundaryDirections,long hiddenDirections,int cachedPairs,long nativeQueries,long pairProbes,long maskBytes,long pairBytes,long knownPeakBytes) {}
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldVisibility.Input> input,Optional<Usage> usage,List<WorldProblem> problems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(input);Objects.requireNonNull(usage);problems=List.copyOf(problems);
            if(problems.isEmpty()!=(input.isPresent()&&usage.isPresent())||!problems.isEmpty()&&(input.isPresent()||usage.isPresent())||input.isPresent()&&(!input.orElseThrow().snapshot().id().equals(snapshotId)||!input.orElseThrow().snapshot().source().equals(source)||!input.orElseThrow().problems().isEmpty()))throw new IllegalArgumentException("Partial native visibility escaped failure");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result capture(NativeRenderContext context,WorldSnapshot snapshot,BooleanSupplier cancelled) { return capture(context,snapshot,Limits.DEFAULT,cancelled); }
    public static Result capture(NativeRenderContext context,WorldSnapshot snapshot,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(snapshot);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        try {
            check(cancelled);context.check();if(snapshot.requiresPlaceholder())return failed(snapshot,snapshot.problems());
            if(!snapshot.source().equals(context.source()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native visibility belongs to another generation receipt");
            long height=(long)context.engine().getMaxHeight()-context.engine().getMinHeight();
            if(snapshot.extent().minY()!=context.engine().getMinHeight()||snapshot.extent().height()!=height||height!=context.engine().getHeight())throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native visibility must preserve complete configured height");
            int capacity=limits.cachedPairs()==0?0:Integer.highestOneBit(limits.cachedPairs()*2-1)<<1;long pairBytes=(long)capacity*9;
            long peak=snapshot.indexBytes()+snapshot.cellCount()+pairBytes;
            if(snapshot.cellCount()>limits.visibility().maskBytes()||peak>limits.visibility().workingBytes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native visibility world/mask/pair-cache payload budget reached");
            var identities=new NativeIdentities(context.biomes(),snapshot.states().size(),1,16L*1024*1024,cancelled);var states=new BlockState[snapshot.states().size()];
            for(int i=0;i<states.length;i++) { check(cancelled);context.checkSession();var expected=snapshot.states().get(i);var handle=Block.stateById(expected.nativeStateId());if(Block.getId(handle)!=expected.nativeStateId()||!identities.state(ModdedBlockState.of(handle,null)).equals(expected))throw NativeIdentities.failure(WorldProblem.Code.STATE,"Native visibility state/domain does not match its captured registry identity");states[i]=handle; }
            var work=new Work(context,snapshot,states,limits,capacity,cancelled);
            var readLimits=new WorldVisibility.Limits(limits.visibility().maskBytes(),limits.visibility().workingBytes()-pairBytes);
            var input=WorldVisibility.Input.read(snapshot,POLICY,work,readLimits,cancelled);context.check();check(cancelled);
            if(!input.problems().isEmpty())return failed(snapshot,input.problems());
            return new Result(snapshot.source(),snapshot.id(),Optional.of(input),Optional.of(new Usage(snapshot.cellCount(),work.inVolume,work.boundaries,work.hidden,work.cached,work.queries,work.probes,input.bytes(),pairBytes,peak)),List.of());
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,List.of(failure.problem())); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native block visibility failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native block visibility; no partial output was published"))); }
    }
    private static final class Work implements WorldVisibility.Reader {
        private static final Direction[] NATIVE={Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST};
        final NativeRenderContext context;final WorldSnapshot snapshot;final BlockState[] states;final Limits limits;final BooleanSupplier cancelled;final long[] keys;final byte[] values;final int width,depth,layer;
        long inVolume,boundaries,hidden,queries,probes;int cached;
        Work(NativeRenderContext context,WorldSnapshot snapshot,BlockState[] states,Limits limits,int capacity,BooleanSupplier cancelled) {
            this.context=context;this.snapshot=snapshot;this.states=states;this.limits=limits;this.cancelled=cancelled;keys=new long[capacity];values=new byte[capacity];width=snapshot.extent().width();depth=snapshot.extent().depth();layer=Math.multiplyExact(width,depth);
        }
        @Override public int hiddenDirections(int cell) {
            if((cell&4095)==0) { check(cancelled);context.checkSession(); }int x=cell%width,z=(cell/width)%depth,y=cell/layer,state=snapshot.stateIndex(cell),mask=0;
            for(int direction=0;direction<6;direction++) {
                int neighbor=switch(direction) { case 0->y==0?-1:cell-layer;case 1->y+1==snapshot.extent().height()?-1:cell+layer;case 2->z==0?-1:cell-width;case 3->z+1==depth?-1:cell+width;case 4->x==0?-1:cell-1;case 5->x+1==width?-1:cell+1;default->throw new AssertionError(); };
                if(neighbor<0) { boundaries++;continue; }inVolume++;int adjacent=snapshot.stateIndex(neighbor);
                if(!visible(state,adjacent,direction)) { mask|=1<<direction;hidden++; }
            }return mask;
        }
        private boolean visible(int state,int adjacent,int direction) {
            long key=((((long)state<<16)|adjacent)<<3|direction)+1;int slot=0;
            if(keys.length>0) {
                long hash=key*0x9e3779b97f4a7c15L;slot=(int)(hash^(hash>>>32))&(keys.length-1);
                while(true) {
                    if(probes>=limits.pairProbes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native face-pair cache probe budget reached");probes++;
                    if((probes&4095)==0) { check(cancelled);context.checkSession(); }
                    if(keys[slot]==0)break;if(keys[slot]==key)return values[slot]==1;slot=(slot+1)&(keys.length-1);
                }
            }
            check(cancelled);context.checkSession();if(queries>=limits.nativeQueries())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native face-query operation budget reached");queries++;
            boolean visible=Block.shouldRenderFace(states[state],states[adjacent],NATIVE[direction]);
            if(cached<limits.cachedPairs()) { keys[slot]=key;values[slot]=(byte)(visible?1:2);cached++; }return visible;
        }
        @Override public void complete() { context.check(); }
    }
    private static Result failed(WorldSnapshot snapshot,List<WorldProblem> problems) { return new Result(snapshot.source(),snapshot.id(),Optional.empty(),Optional.empty(),problems); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native block visibility cancelled"); }
}
