package dev.irisvisualizer.renderer;

import java.util.*;

/** Immutable base-level CPU pages at an external animation clock. No GPU upload has occurred. */
public final class AtlasRaster {
    private final AtlasLayout layout;private final long tick;private final double partialTick;
    private final List<RgbaImage> pages;private final NavigableMap<AssetId,Integer> frames;private final List<ModelPlan.Problem> problems;
    private AtlasRaster(AtlasLayout layout,long tick,double partialTick,List<RgbaImage> pages,Map<AssetId,Integer> frames,List<ModelPlan.Problem> problems) {
        this.layout=Objects.requireNonNull(layout);this.tick=tick;this.partialTick=partialTick;this.pages=List.copyOf(pages);this.frames=Collections.unmodifiableNavigableMap(new TreeMap<>(frames));this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(!pages.isEmpty()||!frames.isEmpty()))throw new IllegalArgumentException("Partial atlas raster escaped failure");
    }
    static AtlasRaster success(AtlasLayout layout,long tick,double partialTick,List<RgbaImage> pages,Map<AssetId,Integer> frames) { return new AtlasRaster(layout,tick,partialTick,pages,frames,List.of()); }
    static AtlasRaster placeholder(AtlasLayout layout,long tick,double partialTick,List<ModelPlan.Problem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new AtlasRaster(layout,tick,partialTick,List.of(),Map.of(),problems); }
    public AtlasLayout layout() { return layout; }
    public ClientAssetSnapshot.Receipt assets() { return layout.assets(); }
    public long tick() { return tick; }
    public double partialTick() { return partialTick; }
    public List<RgbaImage> pages() { return pages; }
    public NavigableMap<AssetId,Integer> frames() { return frames; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public long rgbaBytes() { long bytes=0;for(var page:pages)bytes+=page.bytes();return bytes; }
}
