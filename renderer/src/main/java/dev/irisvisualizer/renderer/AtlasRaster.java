package dev.irisvisualizer.renderer;

import java.util.*;

/** Immutable base-level CPU pages at an external animation clock. No GPU upload has occurred. */
public final class AtlasRaster {
    private final AtlasLayout layout;private final long tick;private final double partialTick;
    private final List<RgbaImage> pages;private final NavigableMap<AssetId,Integer> frames;private final NavigableMap<AssetId,TextureSprite.PixelSample> samples;private final List<ModelPlan.Problem> problems;
    private AtlasRaster(AtlasLayout layout,long tick,double partialTick,List<RgbaImage> pages,Map<AssetId,TextureSprite.PixelSample> samples,List<ModelPlan.Problem> problems) {
        this.layout=Objects.requireNonNull(layout);this.tick=tick;this.partialTick=partialTick;this.pages=List.copyOf(pages);this.samples=Collections.unmodifiableNavigableMap(new TreeMap<>(samples));this.problems=List.copyOf(problems);
        var frames=new TreeMap<AssetId,Integer>();this.samples.forEach((id,sample)->frames.put(id,sample.currentFrame()));this.frames=Collections.unmodifiableNavigableMap(frames);
        if(!problems.isEmpty()&&(!pages.isEmpty()||!samples.isEmpty()))throw new IllegalArgumentException("Partial atlas raster escaped failure");
    }
    static AtlasRaster success(AtlasLayout layout,long tick,double partialTick,List<RgbaImage> pages,Map<AssetId,TextureSprite.PixelSample> samples) { return new AtlasRaster(layout,tick,partialTick,pages,samples,List.of()); }
    static AtlasRaster placeholder(AtlasLayout layout,long tick,double partialTick,List<ModelPlan.Problem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new AtlasRaster(layout,tick,partialTick,List.of(),Map.of(),problems); }
    public AtlasLayout layout() { return layout; }
    public ClientAssetSnapshot.Receipt assets() { return layout.assets(); }
    public long tick() { return tick; }
    public double partialTick() { return partialTick; }
    public List<RgbaImage> pages() { return pages; }
    public NavigableMap<AssetId,Integer> frames() { return frames; }
    /** Complete pixel inputs; frames() alone does not identify interpolated pixels. */
    public NavigableMap<AssetId,TextureSprite.PixelSample> samples() { return samples; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public long rgbaBytes() { long bytes=0;for(var page:pages)bytes+=page.bytes();return bytes; }
}
