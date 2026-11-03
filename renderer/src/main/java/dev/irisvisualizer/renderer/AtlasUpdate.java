package dev.irisvisualizer.renderer;

import java.util.*;

/** Atomic CPU patch transaction, valid only for the exact expected immutable raster object. */
public final class AtlasUpdate {
    /** Tightly packed RGBA rows. x/y include the sprite's complete edge-extruded gutter. */
    public record Patch(AssetId sprite,int page,int x,int y,int frame,RgbaImage pixels) {
        public Patch { Objects.requireNonNull(sprite);Objects.requireNonNull(pixels);if(page<0||x<0||y<0||frame<0)throw new IllegalArgumentException("Invalid atlas patch"); }
        public int rowBytes() { return pixels.width()*4; }
    }
    private final AtlasRaster expected;private final long tick;private final double partialTick;
    private final List<Patch> patches;private final NavigableMap<AssetId,Integer> frames;private final long bytes;private final List<ModelPlan.Problem> problems;
    private AtlasUpdate(AtlasRaster expected,long tick,double partialTick,List<Patch> patches,Map<AssetId,Integer> frames,long bytes,List<ModelPlan.Problem> problems) {
        this.expected=Objects.requireNonNull(expected);this.tick=tick;this.partialTick=partialTick;this.patches=List.copyOf(patches);this.frames=Collections.unmodifiableNavigableMap(new TreeMap<>(frames));this.bytes=bytes;this.problems=List.copyOf(problems);
        if(bytes<0||!problems.isEmpty()&&(!patches.isEmpty()||!frames.isEmpty()||bytes!=0))throw new IllegalArgumentException("Partial atlas update escaped failure");
    }
    static AtlasUpdate success(AtlasRaster expected,long tick,double partialTick,List<Patch> patches,Map<AssetId,Integer> frames,long bytes) { return new AtlasUpdate(expected,tick,partialTick,patches,frames,bytes,List.of()); }
    static AtlasUpdate placeholder(AtlasRaster expected,long tick,double partialTick,List<ModelPlan.Problem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new AtlasUpdate(expected,tick,partialTick,List.of(),Map.of(),0,problems); }
    public AtlasRaster expected() { return expected; }
    public AtlasLayout layout() { return expected.layout(); }
    public long tick() { return tick; }
    public double partialTick() { return partialTick; }
    public List<Patch> patches() { return patches; }
    public NavigableMap<AssetId,Integer> frames() { return frames; }
    public long bytes() { return bytes; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
