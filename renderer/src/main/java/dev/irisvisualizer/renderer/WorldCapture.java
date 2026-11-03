package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded streaming detachment from a caller-frozen native volume; no native handles escape. */
public final class WorldCapture {
    private WorldCapture() {}
    public interface Reader {
        RenderBlockState state(int cell);
        WorldSnapshot.Biome biome(int cell);
        default void complete() {}
    }
    /** Native adapters can report an exact source problem without exposing native objects. */
    public static final class ReadFailure extends RuntimeException {
        private final WorldProblem problem;
        public ReadFailure(WorldProblem problem) { super(Objects.requireNonNull(problem).reason());this.problem=problem; }
        public WorldProblem problem() { return problem; }
    }
    public record Limits(WorldSnapshot.Limits snapshot,long workingBytes,long paletteCharacters) {
        public static final Limits DEFAULT=new Limits(WorldSnapshot.Limits.DEFAULT,256L*1024*1024,16L*1024*1024);
        public Limits { Objects.requireNonNull(snapshot);if(workingBytes<0||workingBytes>512L*1024*1024||paletteCharacters<0||paletteCharacters>16L*1024*1024)throw new IllegalArgumentException("Native capture limits exceed renderer bounds"); }
    }
    public record Result(WorldSnapshot.Source source,WorldSnapshot.Extent extent,Optional<WorldSnapshot> snapshot,List<WorldProblem> problems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(extent);Objects.requireNonNull(snapshot);problems=List.copyOf(problems);
            if(problems.isEmpty()!=snapshot.isPresent()||snapshot.isPresent()&&(snapshot.orElseThrow().requiresPlaceholder()||!snapshot.orElseThrow().source().equals(source)||!snapshot.orElseThrow().extent().equals(extent)))throw new IllegalArgumentException("Partial native volume capture escaped failure");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result capture(WorldSnapshot.Source source,WorldSnapshot.Extent extent,Reader reader,BooleanSupplier cancelled) { return capture(source,extent,reader,Limits.DEFAULT,cancelled); }
    public static Result capture(WorldSnapshot.Source source,WorldSnapshot.Extent extent,Reader reader,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(source);Objects.requireNonNull(extent);Objects.requireNonNull(reader);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(source,extent,reader,limits,cancelled); }
        catch(ReadFailure failure) { WorldProblem.check(cancelled);return failed(source,extent,failure.problem()); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(source,extent,failure.problem); }
        catch(CancellationException cancelledRead) { throw cancelledRead; }
        catch(RuntimeException failure) { WorldProblem.check(cancelled);return failed(source,extent,new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native volume reader failed: "+failure.getClass().getSimpleName())); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(source,extent,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to detach the native volume; no partial snapshot was published")); }
    }
    private static Result failed(WorldSnapshot.Source source,WorldSnapshot.Extent extent,WorldProblem problem) { return new Result(source,extent,Optional.empty(),List.of(problem)); }
    private static Result execute(WorldSnapshot.Source source,WorldSnapshot.Extent extent,Reader reader,Limits limits,BooleanSupplier cancelled) {
        if(!source.minecraftVersion().equals(BlockModels.PROFILE))throw WorldProblem.fail(WorldProblem.Code.VERSION,"Native volume capture requires the pinned Minecraft profile");
        long count=extent.cells();if(count>limits.snapshot().cells()||count*8>limits.snapshot().indexBytes()||count*16>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native volume capture and detached index copy exceed their full-height budget");
        int[] blocks=new int[(int)count],biomes=new int[(int)count];var states=new ArrayList<RenderBlockState>();var palette=new ArrayList<WorldSnapshot.Biome>();
        var stateObjects=new IdentityHashMap<RenderBlockState,Integer>();var biomeObjects=new IdentityHashMap<WorldSnapshot.Biome,Integer>();var stateIds=new HashMap<Integer,Integer>();var biomeIds=new HashMap<Integer,Integer>();long characters=0;
        for(int cell=0;cell<count;cell++) {
            WorldProblem.check(cancelled);var state=Objects.requireNonNull(reader.state(cell));var stateIndex=stateObjects.get(state);
            if(stateIndex==null) {
                stateIndex=stateIds.get(state.nativeStateId());
                if(stateIndex!=null) { if(!states.get(stateIndex).equals(state))throw WorldProblem.at(WorldProblem.Code.STATE,extent.position(cell),"One native state ID has conflicting original identity or property domains"); }
                else {
                    if(states.size()>=limits.snapshot().states())throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Native block-state capture palette limit reached");
                    characters+=characters(state);if(characters>limits.paletteCharacters())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native palette text/domain character budget reached");
                    stateIndex=states.size();states.add(state);stateIds.put(state.nativeStateId(),stateIndex);stateObjects.put(state,stateIndex);
                }
            }
            var biome=Objects.requireNonNull(reader.biome(cell));var biomeIndex=biomeObjects.get(biome);
            if(biomeIndex==null) {
                biomeIndex=biomeIds.get(biome.nativeBiomeId());
                if(biomeIndex!=null) { if(!palette.get(biomeIndex).equals(biome))throw WorldProblem.at(WorldProblem.Code.PALETTE,extent.position(cell),"One native biome ID has conflicting original identity"); }
                else {
                    if(palette.size()>=limits.snapshot().biomes())throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Native biome capture palette limit reached");
                    characters+=characters(biome);if(characters>limits.paletteCharacters())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native palette text/domain character budget reached");
                    biomeIndex=palette.size();palette.add(biome);biomeIds.put(biome.nativeBiomeId(),biomeIndex);biomeObjects.put(biome,biomeIndex);
                }
            }
            blocks[cell]=stateIndex;biomes[cell]=biomeIndex;
        }
        reader.complete();WorldProblem.check(cancelled);var snapshot=WorldSnapshot.capture(source,extent,states,palette,blocks,biomes,limits.snapshot(),cancelled);reader.complete();WorldProblem.check(cancelled);
        return snapshot.requiresPlaceholder()?new Result(source,extent,Optional.empty(),snapshot.problems()):new Result(source,extent,Optional.of(snapshot),List.of());
    }
    static long characters(WorldSnapshot.Biome biome) { return (long)biome.original().length()+biome.id().namespace().length()+biome.id().path().length(); }
    static long characters(RenderBlockState state) {
        long count=(long)state.original().length()+state.block().namespace().length()+state.block().path().length();
        for(var entry:state.properties().entrySet())count+=(long)entry.getKey().length()+entry.getValue().length();
        for(var entry:state.propertyDomain().entrySet()) { count+=entry.getKey().length();for(String value:entry.getValue())count+=value.length(); }return count;
    }
}
