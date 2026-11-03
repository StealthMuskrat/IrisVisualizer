package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.volmlib.nativelib.terrain.NativeBiome;
import art.arcane.volmlib.nativelib.terrain.NativeBlockState;
import art.arcane.volmlib.util.hunk.Hunk;
import dev.irisvisualizer.renderer.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Reads completed caller-frozen native chunks; never invokes generation or substitutes absent cells. */
public final class NativeWorldCapture {
    private NativeWorldCapture() {}
    public static final String PINNED_BUILD=NativeIdentities.BUILD;
    public record Chunk(int x,int z,Hunk<NativeBlockState> blocks,Hunk<NativeBiome> biomes) { public Chunk { Objects.requireNonNull(blocks);Objects.requireNonNull(biomes); } }
    public record Limits(WorldCapture.Limits capture,int chunks) {
        public static final Limits DEFAULT=new Limits(WorldCapture.Limits.DEFAULT,4096);
        public Limits { Objects.requireNonNull(capture);if(chunks<1||chunks>65536)throw new IllegalArgumentException("Native chunk capture limit exceeded"); }
    }
    public static WorldCapture.Result capture(NativeRenderContext context,WorldSnapshot.Extent extent,List<Chunk> chunks,BooleanSupplier cancelled) { return capture(context,extent,chunks,Limits.DEFAULT,cancelled); }
    public static WorldCapture.Result capture(NativeRenderContext context,WorldSnapshot.Extent extent,List<Chunk> chunks,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(extent);Objects.requireNonNull(chunks);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        var engine=context.engine();var source=context.source();
        try {
            check(cancelled);context.check();long height=(long)engine.getMaxHeight()-engine.getMinHeight();
            if(extent.minY()!=engine.getMinHeight()||extent.height()!=height||height!=engine.getHeight())throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native capture must retain the engine's complete configured vertical range");
            if(extent.minX()<Integer.MIN_VALUE||extent.minZ()<Integer.MIN_VALUE||extent.maxX()-1>Integer.MAX_VALUE||extent.maxZ()-1>Integer.MAX_VALUE)throw NativeIdentities.failure(WorldProblem.Code.COORDINATE,"Native chunk capture exceeds signed 32-bit block coordinates");
            int minX=(int)Math.floorDiv(extent.minX(),16),minZ=(int)Math.floorDiv(extent.minZ(),16),maxX=(int)Math.floorDiv(extent.maxX()-1,16),maxZ=(int)Math.floorDiv(extent.maxZ()-1,16);
            long expected=((long)maxX-minX+1)*((long)maxZ-minZ+1);
            if(expected>limits.chunks()||chunks.size()!=expected)throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Supply exactly the bounded native chunks covering the entire requested area");
            var captured=List.copyOf(chunks);var table=new HashMap<Long,Chunk>();
            for(var chunk:captured) {
                check(cancelled);if(chunk.x()<minX||chunk.x()>maxX||chunk.z()<minZ||chunk.z()>maxZ||table.put(key(chunk.x(),chunk.z()),chunk)!=null)throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Duplicate or out-of-area native chunk");
                if(chunk.blocks().getWidth()!=16||chunk.blocks().getDepth()!=16||chunk.blocks().getHeight()!=height||chunk.biomes().getWidth()!=16||chunk.biomes().getDepth()!=16||chunk.biomes().getHeight()!=height)throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native chunk arrays do not cover 16 by full configured height by 16");
            }
            var bounds=limits.capture();var identities=new NativeIdentities(context.biomes(),bounds.snapshot().states(),bounds.snapshot().biomes(),bounds.paletteCharacters(),cancelled);
            return WorldCapture.capture(source,extent,new WorldCapture.Reader() {
                private int current=-1,x,y,z;private Chunk chunk;
                private void cell(int index) {
                    context.checkSession();if(current==index)return;current=index;long layer=(long)extent.width()*extent.depth();int worldX=(int)(extent.minX()+index%extent.width()),worldZ=(int)(extent.minZ()+(index/extent.width())%extent.depth());
                    y=(int)(index/layer);x=Math.floorMod(worldX,16);z=Math.floorMod(worldZ,16);chunk=table.get(key(Math.floorDiv(worldX,16),Math.floorDiv(worldZ,16)));
                    if(chunk==null)throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"A required native chunk is absent");
                }
                @Override public RenderBlockState state(int index) { cell(index);return identities.state(chunk.blocks().get(x,y,z)); }
                @Override public WorldSnapshot.Biome biome(int index) { cell(index);return identities.biome(chunk.biomes().get(x,y,z)); }
                @Override public void complete() { context.check(); }
            },bounds,cancelled);
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(source,extent,failure.problem()); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(source,extent,new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native chunk capture failed: "+failure.getClass().getSimpleName())); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(source,extent,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to detach native chunk input")); }
    }
    private static long key(int x,int z) { return (long)x<<32|z&0xffffffffL; }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native chunk capture cancelled"); }
    private static WorldCapture.Result failed(WorldSnapshot.Source source,WorldSnapshot.Extent extent,WorldProblem problem) { return new WorldCapture.Result(source,extent,Optional.empty(),List.of(problem)); }
}
