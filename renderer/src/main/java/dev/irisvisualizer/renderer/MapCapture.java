package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Worker-side detachment of native samples; the supplied sampler owns every generation decision. */
public final class MapCapture {
    private MapCapture() {}
    public interface Sampler {
        default boolean present(double x,double z) { return true; }
        default int height(double x,double z) { throw new UnsupportedOperationException("No native height sampler"); }
        default double noise(double x,double z) { throw new UnsupportedOperationException("No native noise sampler"); }
        default WorldSnapshot.Biome biome(double x,double z) { throw new UnsupportedOperationException("No native biome sampler"); }
        default void complete() {}
    }
    public record Limits(MapSamples.Limits samples,long workingBytes,long paletteCharacters) {
        public static final Limits DEFAULT=new Limits(MapSamples.Limits.DEFAULT,128L*1024*1024,1024L*1024);
        public Limits { Objects.requireNonNull(samples);if(workingBytes<0||workingBytes>128L*1024*1024||paletteCharacters<0||paletteCharacters>1024L*1024)throw new IllegalArgumentException("Native map capture limits exceed renderer bounds"); }
    }
    public static MapSamples capture(MapSamples.Context context,MapSamples.Grid grid,MapSamples.Kind kind,Optional<MapSamples.Vertical> vertical,Sampler sampler,BooleanSupplier cancelled) { return capture(context,grid,kind,vertical,sampler,Limits.DEFAULT,cancelled); }
    public static MapSamples capture(MapSamples.Context context,MapSamples.Grid grid,MapSamples.Kind kind,Optional<MapSamples.Vertical> vertical,Sampler sampler,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(grid);Objects.requireNonNull(kind);Objects.requireNonNull(vertical);Objects.requireNonNull(sampler);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(context,grid,kind,vertical,sampler,limits,cancelled); }
        catch(WorldCapture.ReadFailure failure) { WorldProblem.check(cancelled);return MapSamples.failed(context,grid,kind,vertical,failure.problem()); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return MapSamples.failed(context,grid,kind,vertical,failure.problem); }
        catch(CancellationException cancelledRead) { throw cancelledRead; }
        catch(RuntimeException failure) { WorldProblem.check(cancelled);return MapSamples.failed(context,grid,kind,vertical,new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native map sampler failed: "+failure.getClass().getSimpleName())); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return MapSamples.failed(context,grid,kind,vertical,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to detach native map samples; no partial map was published")); }
    }
    private static MapSamples execute(MapSamples.Context context,MapSamples.Grid grid,MapSamples.Kind kind,Optional<MapSamples.Vertical> vertical,Sampler sampler,Limits limits,BooleanSupplier cancelled) {
        if(!context.world().minecraftVersion().equals(BlockModels.PROFILE))throw WorldProblem.fail(WorldProblem.Code.VERSION,"Native sampling requires the pinned Minecraft profile");
        long count=grid.count();var bound=limits.samples();
        if(grid.width()>bound.dimension()||grid.height()>bound.dimension()||count>bound.samples())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native map sampling exceeds its grid/count budget");
        long bytes=count*(kind==MapSamples.Kind.NOISE?9:5);
        if(bytes>bound.payloadBytes()||bytes*2>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native map sampling and detached copy exceed their grid/payload budget");
        if(vertical.isPresent()!=(kind==MapSamples.Kind.TERRAIN))throw WorldProblem.fail(WorldProblem.Code.EXTENT,"Supply full dimension height only for terrain sampling");
        int[] integers=kind==MapSamples.Kind.NOISE?new int[0]:new int[(int)count];double[] values=kind==MapSamples.Kind.NOISE?new double[(int)count]:new double[0];byte[] present=new byte[(int)count];
        var palette=new ArrayList<WorldSnapshot.Biome>();var ids=new HashMap<Integer,Integer>();var objects=new IdentityHashMap<WorldSnapshot.Biome,Integer>();long characters=0;
        for(int z=0,at=0;z<grid.height();z++)for(int x=0;x<grid.width();x++,at++) {
            WorldProblem.check(cancelled);double worldX=grid.worldX(x),worldZ=grid.worldZ(z);if(!sampler.present(worldX,worldZ))continue;present[at]=1;
            switch(kind) {
                case NOISE->{ double value=sampler.noise(worldX,worldZ);if(!Double.isFinite(value))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Native noise sampling returned a nonfinite value");values[at]=value; }
                case TERRAIN->{ int height=sampler.height(worldX,worldZ);var range=vertical.orElseThrow();if(height<range.minY()||(long)height>=range.maxY())throw WorldProblem.fail(WorldProblem.Code.EXTENT,"Native height sampling returned a value outside its full configured dimension");integers[at]=height; }
                case BIOME->{
                    var biome=Objects.requireNonNull(sampler.biome(worldX,worldZ));var index=objects.get(biome);
                    if(index==null) { index=ids.get(biome.nativeBiomeId());if(index!=null) { if(!palette.get(index).equals(biome))throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Native biome sampler returned conflicting identity for one registry ID"); }
                        else { if(palette.size()>=bound.biomes())throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Native map biome palette count reached");characters+=WorldCapture.characters(biome);if(characters>limits.paletteCharacters())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native map biome identity text limit reached");index=palette.size();palette.add(biome);ids.put(biome.nativeBiomeId(),index);objects.put(biome,index); }
                    }integers[at]=index;
                }
            }
        }
        // An empty biome map still needs an explicit native palette; never fabricate a default biome.
        if(kind==MapSamples.Kind.BIOME&&palette.isEmpty())throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Native biome sampling returned no present identity");
        sampler.complete();WorldProblem.check(cancelled);var result=MapSamples.capture(context,grid,kind,vertical,palette,integers,values,present,bound,cancelled);sampler.complete();WorldProblem.check(cancelled);return result;
    }
}
