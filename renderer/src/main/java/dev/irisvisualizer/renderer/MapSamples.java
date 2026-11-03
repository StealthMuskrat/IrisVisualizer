package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached native map output, X fastest then positive Z. Sampling remains an engine operation. */
public final class MapSamples {
    public enum Kind { TERRAIN, BIOME, NOISE }
    public record Context(WorldSnapshot.Source world,String resourceType,String resourceKey,String samplingPolicy) {
        public Context { Objects.requireNonNull(world);for(String text:List.of(resourceType,resourceKey,samplingPolicy))if(text.isBlank()||text.length()>1024)throw new IllegalArgumentException("Map context requires bounded exact resource and sampling identities"); }
    }
    /** Origin is the first sample, matching the editor's framing; scale never changes generation. */
    public record Grid(int originX,int originZ,int width,int height,double blocksPerSample) {
        public static final int DEFAULT_SAMPLES=512;
        public Grid {
            if(width<1||height<1||!Double.isFinite(blocksPerSample)||blocksPerSample<=0)throw new IllegalArgumentException("Invalid map grid");
            double x=originX+(width-1)*blocksPerSample,z=originZ+(height-1)*blocksPerSample;
            if(!Double.isFinite(x)||!Double.isFinite(z)||x>Integer.MAX_VALUE||z>Integer.MAX_VALUE)throw new IllegalArgumentException("Map extends beyond signed 32-bit world coordinates; explicitly reduce its scale or area");
        }
        public Grid(int x,int z,double scale) { this(x,z,DEFAULT_SAMPLES,DEFAULT_SAMPLES,scale); }
        public long count() { return (long)width*height; }
        public int index(int x,int z) { if(x<0||x>=width||z<0||z>=height)throw new IndexOutOfBoundsException("Pixel outside map grid");return Math.toIntExact((long)z*width+x); }
        public double worldX(int x) { if(x<0||x>=width)throw new IndexOutOfBoundsException("Column outside map");return originX+x*blocksPerSample; }
        public double worldZ(int z) { if(z<0||z>=height)throw new IndexOutOfBoundsException("Row outside map");return originZ+z*blocksPerSample; }
    }
    public record Vertical(int minY,int height) {
        public Vertical { if(height<1||(long)minY+height-1>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid full dimension height"); }
        public long maxY() { return (long)minY+height; }
    }
    public record Limits(int dimension,long samples,int biomes,long payloadBytes) {
        public static final Limits DEFAULT=new Limits(4096,4194304,8192,64L*1024*1024);
        public Limits { if(dimension<1||dimension>4096||samples<1||samples>4194304||biomes<1||biomes>8192||payloadBytes<0||payloadBytes>64L*1024*1024)throw new IllegalArgumentException("Map limits exceed renderer bounds"); }
    }
    private final Context context;private final Grid grid;private final Kind kind;private final Optional<Vertical> vertical;private final List<WorldSnapshot.Biome> biomes;
    private final int[] integers;private final double[] noise;private final byte[] present;private final List<WorldProblem> problems;
    private MapSamples(Context context,Grid grid,Kind kind,Optional<Vertical> vertical,List<WorldSnapshot.Biome> biomes,int[] integers,double[] noise,byte[] present,List<WorldProblem> problems) {
        this.context=context;this.grid=grid;this.kind=kind;this.vertical=vertical;this.biomes=List.copyOf(biomes);this.integers=integers;this.noise=noise;this.present=present;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(integers.length!=0||noise.length!=0||present.length!=0||!biomes.isEmpty()))throw new IllegalArgumentException("Partial native map escaped failure");
    }
    public static MapSamples terrain(Context context,Grid grid,Vertical vertical,int[] heights,byte[] present,BooleanSupplier cancelled) { return capture(context,grid,Kind.TERRAIN,Optional.of(vertical),List.of(),heights,new double[0],present,Limits.DEFAULT,cancelled); }
    public static MapSamples biomes(Context context,Grid grid,List<WorldSnapshot.Biome> palette,int[] indices,byte[] present,BooleanSupplier cancelled) { return capture(context,grid,Kind.BIOME,Optional.empty(),palette,indices,new double[0],present,Limits.DEFAULT,cancelled); }
    public static MapSamples noise(Context context,Grid grid,double[] values,byte[] present,BooleanSupplier cancelled) { return capture(context,grid,Kind.NOISE,Optional.empty(),List.of(),new int[0],values,present,Limits.DEFAULT,cancelled); }
    /** Frozen caller arrays/list are required through capture. Presence is exactly zero or one. */
    public static MapSamples capture(Context context,Grid grid,Kind kind,Optional<Vertical> vertical,List<WorldSnapshot.Biome> palette,int[] integers,double[] noise,byte[] present,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(grid);Objects.requireNonNull(kind);Objects.requireNonNull(vertical);Objects.requireNonNull(palette);Objects.requireNonNull(integers);Objects.requireNonNull(noise);Objects.requireNonNull(present);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(context,grid,kind,vertical,palette,integers,noise,present,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(context,grid,kind,vertical,failure.problem); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(context,grid,kind,vertical,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native map samples; no partial map was published")); }
    }
    private static MapSamples execute(Context context,Grid grid,Kind kind,Optional<Vertical> vertical,List<WorldSnapshot.Biome> palette,int[] integers,double[] noise,byte[] present,Limits limits,BooleanSupplier cancelled) {
        if(!context.world().minecraftVersion().equals(BlockModels.PROFILE))throw WorldProblem.fail(WorldProblem.Code.VERSION,"Map samples require the pinned Minecraft profile");
        long count=grid.count();
        if(grid.width()>limits.dimension()||grid.height()>limits.dimension()||count>limits.samples())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native map dimension or sample limit exceeded; no samples were omitted");
        long bytes=count*(kind==Kind.NOISE?9:5);if(bytes>limits.payloadBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native map payload limit exceeded; no samples were omitted");
        if(present.length!=count||integers.length!=(kind==Kind.NOISE?0:count)||noise.length!=(kind==Kind.NOISE?count:0)||vertical.isPresent()!=(kind==Kind.TERRAIN)||kind!=Kind.BIOME&&!palette.isEmpty())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Map arrays and full-height context do not match the selected map kind");
        if(kind==Kind.BIOME&&(palette.isEmpty()||palette.size()>limits.biomes()))throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Map biome palette exceeds its bounds");
        var identities=new HashSet<Integer>();var copiedPalette=new ArrayList<WorldSnapshot.Biome>(palette.size());
        for(var biome:palette) { WorldProblem.check(cancelled);if(biome==null||!identities.add(biome.nativeBiomeId()))throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Null or duplicate native biome ID in map palette");copiedPalette.add(biome); }
        int[] values=kind==Kind.NOISE?new int[0]:new int[(int)count];double[] scalar=kind==Kind.NOISE?new double[(int)count]:new double[0];byte[] coverage=new byte[(int)count];
        for(int at=0;at<count;at++) {
            if((at&4095)==0)WorldProblem.check(cancelled);byte flag=present[at];if(flag!=0&&flag!=1)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Map presence must contain only zero/one flags");coverage[at]=flag;if(flag==0)continue;
            if(kind==Kind.NOISE) { double value=noise[at];if(!Double.isFinite(value))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Native noise map contains a nonfinite present value");scalar[at]=value; }
            else { int value=integers[at];if(kind==Kind.BIOME&&(value<0||value>=copiedPalette.size()))throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Map sample references an absent native biome");if(kind==Kind.TERRAIN&&(value<vertical.orElseThrow().minY()||(long)value>=vertical.orElseThrow().maxY()))throw WorldProblem.fail(WorldProblem.Code.EXTENT,"Map height lies outside the full configured dimension range");values[at]=value; }
        }
        WorldProblem.check(cancelled);return new MapSamples(context,grid,kind,vertical,copiedPalette,values,scalar,coverage,List.of());
    }
    private static MapSamples failed(Context context,Grid grid,Kind kind,Optional<Vertical> vertical,WorldProblem problem) { return new MapSamples(context,grid,kind,vertical,List.of(),new int[0],new double[0],new byte[0],List.of(problem)); }
    private void sample(int at) { if(requiresPlaceholder())throw new IllegalStateException("Map capture failed");if(at<0||at>=present.length)throw new IndexOutOfBoundsException("Sample outside map");if(present[at]==0)throw new IllegalStateException("Native sample is absent"); }
    public Context context() { return context; }
    public Grid grid() { return grid; }
    public Kind kind() { return kind; }
    public Optional<Vertical> vertical() { return vertical; }
    public List<WorldSnapshot.Biome> biomePalette() { return biomes; }
    public int size() { return present.length; }
    public long payloadBytes() { return (long)integers.length*4+(long)noise.length*8+present.length; }
    public boolean present(int at) { if(requiresPlaceholder())throw new IllegalStateException("Map capture failed");return present[at]!=0; }
    public int height(int at) { sample(at);if(kind!=Kind.TERRAIN)throw new IllegalStateException("Map has no terrain heights");return integers[at]; }
    public double noise(int at) { sample(at);if(kind!=Kind.NOISE)throw new IllegalStateException("Map has no noise values");return noise[at]; }
    public int biomeIndex(int at) { sample(at);if(kind!=Kind.BIOME)throw new IllegalStateException("Map has no biome indices");return integers[at]; }
    public WorldSnapshot.Biome biome(int at) { return biomes.get(biomeIndex(at)); }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
