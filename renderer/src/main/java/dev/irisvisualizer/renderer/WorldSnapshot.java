package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached native block/biome palette indices. This data receipt does not establish world parity. */
public final class WorldSnapshot {
    private final UUID id=UUID.randomUUID();
    public record Source(UUID generationId,String inputSha256,String backendBuild,String minecraftVersion,AssetId dimension,long seed) {
        public Source {
            Objects.requireNonNull(generationId);Objects.requireNonNull(inputSha256);Objects.requireNonNull(backendBuild);Objects.requireNonNull(minecraftVersion);Objects.requireNonNull(dimension);
            if(!inputSha256.matches("[0-9a-f]{64}")||backendBuild.isBlank()||backendBuild.length()>256||minecraftVersion.isBlank()||minecraftVersion.length()>128)throw new IllegalArgumentException("Invalid native generation receipt");
        }
    }
    public record Position(long x,int y,long z) {}
    /** Half-open extents. Compact order is X fastest, then Z, then Y; configured height is retained. */
    public record Extent(long minX,int minY,long minZ,int width,int height,int depth) {
        public Extent {
            if(width<1||height<1||depth<1||(long)minY+height-1>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid world extent");
            Math.addExact(minX,width);Math.addExact(minZ,depth);Math.multiplyExact(Math.multiplyExact((long)width,depth),height);
        }
        public long cells() { return (long)width*depth*height; }
        public long maxX() { return minX+width; }
        public long maxY() { return (long)minY+height; }
        public long maxZ() { return minZ+depth; }
        public boolean contains(Position p) { return p.x()>=minX&&p.x()<maxX()&&p.y()>=minY&&(long)p.y()<maxY()&&p.z()>=minZ&&p.z()<maxZ(); }
        public boolean contains(Extent other) { return other.minX>=minX&&other.maxX()<=maxX()&&other.minY>=minY&&other.maxY()<=maxY()&&other.minZ>=minZ&&other.maxZ()<=maxZ(); }
        public int index(Position position) {
            if(!contains(position))throw new IndexOutOfBoundsException("Position outside captured extent");
            return Math.toIntExact(((long)position.y()-minY)*width*depth+(position.z()-minZ)*width+(position.x()-minX));
        }
        public Position position(int index) {
            if(index<0||index>=cells())throw new IndexOutOfBoundsException("Cell index outside captured extent");
            long layer=(long)width*depth;return new Position(minX+index%width,(int)(minY+index/layer),minZ+(index/width)%depth);
        }
    }
    public record Biome(int nativeBiomeId,String original,AssetId id) {
        public Biome { Objects.requireNonNull(original);Objects.requireNonNull(id);if(nativeBiomeId<0||original.isEmpty()||original.length()>16384)throw new IllegalArgumentException("Invalid native biome identity"); }
    }
    public record Limits(int states,int biomes,long cells,long indexBytes) {
        public static final Limits DEFAULT=new Limits(65536,8192,16777216,256L*1024*1024);
        public Limits { if(states<1||states>65536||biomes<1||biomes>8192||cells<1||cells>16777216||indexBytes<0||indexBytes>256L*1024*1024)throw new IllegalArgumentException("World snapshot limits exceed renderer bounds"); }
    }
    private final Source source;private final Extent extent;private final List<RenderBlockState> states;private final List<Biome> biomes;
    private final int[] stateIndices,biomeIndices;private final List<WorldProblem> problems;
    private WorldSnapshot(Source source,Extent extent,List<RenderBlockState> states,List<Biome> biomes,int[] ownedStates,int[] ownedBiomes,List<WorldProblem> problems) {
        this.source=source;this.extent=extent;this.states=List.copyOf(states);this.biomes=List.copyOf(biomes);stateIndices=ownedStates;biomeIndices=ownedBiomes;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(!states.isEmpty()||!biomes.isEmpty()||ownedStates.length!=0||ownedBiomes.length!=0))throw new IllegalArgumentException("Partial world snapshot escaped failure");
    }
    public static WorldSnapshot capture(Source source,Extent extent,List<RenderBlockState> states,List<Biome> biomes,int[] stateIndices,int[] biomeIndices,BooleanSupplier cancelled) { return capture(source,extent,states,biomes,stateIndices,biomeIndices,Limits.DEFAULT,cancelled); }
    public static WorldSnapshot capture(Source source,Extent extent,List<RenderBlockState> states,List<Biome> biomes,int[] stateIndices,int[] biomeIndices,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(source);Objects.requireNonNull(extent);Objects.requireNonNull(states);Objects.requireNonNull(biomes);Objects.requireNonNull(stateIndices);Objects.requireNonNull(biomeIndices);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(source,extent,states,biomes,stateIndices,biomeIndices,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return placeholder(source,extent,failure.problem); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return placeholder(source,extent,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native voxel indices; no partial world was published")); }
    }
    private static WorldSnapshot execute(Source source,Extent extent,List<RenderBlockState> states,List<Biome> biomes,int[] stateInput,int[] biomeInput,Limits limits,BooleanSupplier cancelled) {
        if(!source.minecraftVersion().equals(BlockModels.PROFILE))throw WorldProblem.fail(WorldProblem.Code.VERSION,"World snapshot requires the pinned native Minecraft profile");
        long cells=extent.cells();if(cells>limits.cells()||cells*8>limits.indexBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native voxel index budget exceeded; explicitly reduce area rather than truncating configured height");
        if(stateInput.length!=cells||biomeInput.length!=cells)throw WorldProblem.fail(WorldProblem.Code.EXTENT,"Supply one native state and biome palette index for every cell");
        if(states.isEmpty()||states.size()>limits.states()||biomes.isEmpty()||biomes.size()>limits.biomes())throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Native state or biome palette count is outside its budget");
        var nativeIds=new HashSet<Integer>();var capturedStates=new ArrayList<RenderBlockState>();
        for(var state:states) { WorldProblem.check(cancelled);if(capturedStates.size()>=limits.states())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native state palette budget reached");Objects.requireNonNull(state);if(!nativeIds.add(state.nativeStateId()))throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Duplicate native block-state ID in palette");capturedStates.add(state); }
        nativeIds.clear();var capturedBiomes=new ArrayList<Biome>();
        for(var biome:biomes) { WorldProblem.check(cancelled);if(capturedBiomes.size()>=limits.biomes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native biome palette budget reached");Objects.requireNonNull(biome);if(!nativeIds.add(biome.nativeBiomeId()))throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Duplicate native biome ID in palette");capturedBiomes.add(biome); }
        int[] stateCopy=new int[(int)cells],biomeCopy=new int[(int)cells];
        for(int i=0;i<cells;i++) {
            if((i&4095)==0)WorldProblem.check(cancelled);int state=stateInput[i],biome=biomeInput[i];
            if(state<0||state>=capturedStates.size()||biome<0||biome>=capturedBiomes.size())throw WorldProblem.at(WorldProblem.Code.PALETTE,extent.position(i),"Native voxel index references an absent palette entry");stateCopy[i]=state;biomeCopy[i]=biome;
        }
        WorldProblem.check(cancelled);return new WorldSnapshot(source,extent,capturedStates,capturedBiomes,stateCopy,biomeCopy,List.of());
    }
    private static WorldSnapshot placeholder(Source source,Extent extent,WorldProblem problem) { return new WorldSnapshot(source,extent,List.of(),List.of(),new int[0],new int[0],List.of(problem)); }
    /** Exact capture identity, independent of caller-supplied generation receipt values. */
    public UUID id() { return id; }
    public Source source() { return source; }
    public Extent extent() { return extent; }
    public List<RenderBlockState> states() { return states; }
    public List<Biome> biomes() { return biomes; }
    public int cellCount() { return stateIndices.length; }
    public long indexBytes() { return (long)(stateIndices.length+biomeIndices.length)*Integer.BYTES; }
    public int stateIndex(int cell) { return stateIndices[cell]; }
    public int biomeIndex(int cell) { return biomeIndices[cell]; }
    public RenderBlockState state(int cell) { return states.get(stateIndices[cell]); }
    public Biome biome(int cell) { return biomes.get(biomeIndices[cell]); }
    public int[] copyStateIndices() { return stateIndices.clone(); }
    public int[] copyBiomeIndices() { return biomeIndices.clone(); }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
