package dev.irisvisualizer.renderer;

import java.util.*;

/** Immutable decoded sprite sheet and animation schedule; owns no GPU object or clock. */
public final class TextureSprite {
    public enum MipmapStrategy { MEAN, CUTOUT, STRICT_CUTOUT, DARK_CUTOUT }
    /** Retained for later atlas/filter implementation; no mipmaps or interpolation are generated here. */
    public record Settings(boolean blur,boolean clamp,MipmapStrategy mipmapStrategy,float alphaCutoffBias) {
        public Settings { Objects.requireNonNull(mipmapStrategy);if(!Float.isFinite(alphaCutoffBias))throw new IllegalArgumentException("Nonfinite alpha cutoff bias"); }
    }
    public record Frame(int index,int ticks) {
        public Frame { if(index<0||ticks<1)throw new IllegalArgumentException("Invalid animation frame"); }
    }
    public record Tile(int x,int y,int width,int height) {
        public Tile { if(x<0||y<0||width<1||height<1)throw new IllegalArgumentException("Invalid animation tile"); }
    }
    /** Continuous scheduling metadata; pixel playback uses the separately quantized pixelSample. */
    public record Sample(int sequenceIndex,int currentFrame,int nextFrame,double blend) {}
    /** Canonical base-level pixel inputs. A zero blend selects exactly one tile. */
    public record PixelSample(int currentFrame,int nextFrame,int blendSteps) {
        public PixelSample {
            if(currentFrame<0||nextFrame<0||blendSteps<0||blendSteps>1000)throw new IllegalArgumentException("Invalid sprite pixel sample");
            if(blendSteps==1000) { currentFrame=nextFrame;blendSteps=0; }
            if(blendSteps==0||currentFrame==nextFrame) { nextFrame=currentFrame;blendSteps=0; }
        }
        public boolean blended() { return blendSteps!=0; }
    }
    /** Inclusive conservative interval selecting the same canonical pixel inputs; never compares image pixels. */
    public record PixelWindow(long firstTick,long lastTick,PixelSample sample) {
        public PixelWindow { Objects.requireNonNull(sample);if(firstTick<0||lastTick<firstTick)throw new IllegalArgumentException("Invalid pixel clock interval"); }
    }
    private final AssetId id;private final String pngSha256;private final Optional<String> metadataSha256;
    private final RgbaImage image;private final Settings settings;private final int frameWidth,frameHeight,columns,tiles;
    private final boolean animated,interpolate;private final List<Frame> frames;private final List<Integer> referencedFrames;
    private final long[] ends;private final long cycleTicks;private final PixelSample fixedPixels;
    TextureSprite(AssetId id,String pngSha256,Optional<String> metadataSha256,RgbaImage image,TextureMetadata.Parsed metadata) {
        this.id=Objects.requireNonNull(id);this.pngSha256=Objects.requireNonNull(pngSha256);this.metadataSha256=Objects.requireNonNull(metadataSha256);this.image=Objects.requireNonNull(image);
        settings=metadata.settings();frameWidth=metadata.width();frameHeight=metadata.height();animated=metadata.animated();interpolate=metadata.interpolate();frames=List.copyOf(metadata.frames());
        if(frameWidth<1||frameHeight<1||image.width()%frameWidth!=0||image.height()%frameHeight!=0||frames.isEmpty()||frames.size()>16384)throw new IllegalArgumentException("Invalid sprite schedule");
        columns=image.width()/frameWidth;tiles=columns*(image.height()/frameHeight);ends=new long[frames.size()];long ticks=0;var used=new TreeSet<Integer>();
        for(int i=0;i<frames.size();i++) { var frame=frames.get(i);if(frame.index()>=tiles)throw new IllegalArgumentException("Frame outside sprite sheet");ticks+=frame.ticks();ends[i]=ticks;used.add(frame.index()); }
        cycleTicks=ticks;referencedFrames=List.copyOf(used);fixedPixels=used.size()==1?new PixelSample(used.first(),used.first(),0):null;
    }
    public AssetId id() { return id; }
    public String pngSha256() { return pngSha256; }
    public Optional<String> metadataSha256() { return metadataSha256; }
    public RgbaImage image() { return image; }
    public Settings settings() { return settings; }
    public int frameWidth() { return frameWidth; }
    public int frameHeight() { return frameHeight; }
    public int tileCount() { return tiles; }
    /** Effective playback state, not merely presence of an animation metadata section. */
    public boolean animated() { return animated; }
    public boolean interpolate() { return interpolate; }
    public List<Frame> frames() { return frames; }
    public List<Integer> referencedFrames() { return referencedFrames; }
    public long cycleTicks() { return cycleTicks; }
    /** More than one canonical source tile can be selected; repeated single-tile schedules remain time-invariant. */
    public boolean timeVarying() { return fixedPixels==null; }
    public Tile tile(int index) { if(index<0||index>=tiles)throw new IndexOutOfBoundsException("Frame outside sprite sheet");return new Tile(index%columns*frameWidth,index/columns*frameHeight,frameWidth,frameHeight); }
    /** Nonnegative integer ticks avoid precision loss at large clocks; partialTick is in [0,1). */
    public Sample sample(long tick,double partialTick) {
        clock(tick,partialTick);long time=tick%cycleTicks;int low=slot(time);
        long start=low==0?0:ends[low-1];double blend=interpolate?Math.min(Math.nextDown(1.0),(time-start+partialTick)/frames.get(low).ticks()):0;
        return new Sample(low,frames.get(low).index(),frames.get((low+1)%frames.size()).index(),blend);
    }
    /**
     * Reviewed 26.3 playback: integer sub-frame ticks, float division and thousandth quantization.
     * Partial ticks are validated for API consistency but do not advance pixel interpolation.
     */
    public PixelSample pixelSample(long tick,double partialTick) {
        clock(tick,partialTick);if(fixedPixels!=null)return fixedPixels;
        long time=tick%cycleTicks;int slot=slot(time);return pixels(slot,steps(slot,time-(slot==0?0:ends[slot-1])));
    }
    /** Metadata-only interval; interpolation uses the exact float/thousandth arithmetic of pixelSample. */
    public PixelWindow pixelWindow(long tick,double partialTick) {
        clock(tick,partialTick);if(fixedPixels!=null)return new PixelWindow(tick,Long.MAX_VALUE,fixedPixels);
        long time=tick%cycleTicks;int slot=slot(time),duration=frames.get(slot).ticks();long elapsed=time-(slot==0?0:ends[slot-1]);int steps=steps(slot,elapsed);
        long firstChange=duration;
        if(interpolate&&frames.get(slot).index()!=frames.get((slot+1)%frames.size()).index()) {
            // Positive float division/quantization is monotone. Search within this entry only;
            // its boundary is a conservative recheck even when the next entry has equal pixels.
            long low=elapsed+1,high=duration;
            while(low<high) { long middle=(low+high)>>>1;if(steps(slot,middle)==steps)low=middle+1;else high=middle; }
            firstChange=low;
        }
        long remaining=firstChange-elapsed-1,last=tick>Long.MAX_VALUE-remaining?Long.MAX_VALUE:tick+remaining;
        return new PixelWindow(tick,last,pixels(slot,steps));
    }
    private static void clock(long tick,double partialTick) { if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid animation clock"); }
    private int slot(long time) {
        int low=0,high=ends.length-1;while(low<high) { int middle=(low+high)>>>1;if(time<ends[middle])high=middle;else low=middle+1; }return low;
    }
    private int steps(int slot,long elapsed) { return interpolate&&frames.get(slot).index()!=frames.get((slot+1)%frames.size()).index()?Math.clamp((int)(((float)elapsed/frames.get(slot).ticks())*1000.0f),0,1000):0; }
    private PixelSample pixels(int slot,int steps) { return new PixelSample(frames.get(slot).index(),frames.get((slot+1)%frames.size()).index(),steps); }
}
