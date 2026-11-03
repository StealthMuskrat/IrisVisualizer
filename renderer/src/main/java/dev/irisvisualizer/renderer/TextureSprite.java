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
    /** Blend fraction is scheduling metadata only. Native pixel interpolation remains unimplemented. */
    public record Sample(int sequenceIndex,int currentFrame,int nextFrame,double blend) {}
    private final AssetId id;private final String pngSha256;private final Optional<String> metadataSha256;
    private final RgbaImage image;private final Settings settings;private final int frameWidth,frameHeight,columns,tiles;
    private final boolean animated,interpolate;private final List<Frame> frames;private final List<Integer> referencedFrames;
    private final long[] ends;private final long cycleTicks;
    TextureSprite(AssetId id,String pngSha256,Optional<String> metadataSha256,RgbaImage image,TextureMetadata.Parsed metadata) {
        this.id=Objects.requireNonNull(id);this.pngSha256=Objects.requireNonNull(pngSha256);this.metadataSha256=Objects.requireNonNull(metadataSha256);this.image=Objects.requireNonNull(image);
        settings=metadata.settings();frameWidth=metadata.width();frameHeight=metadata.height();animated=metadata.animated();interpolate=metadata.interpolate();frames=List.copyOf(metadata.frames());
        if(frameWidth<1||frameHeight<1||image.width()%frameWidth!=0||image.height()%frameHeight!=0||frames.isEmpty()||frames.size()>16384)throw new IllegalArgumentException("Invalid sprite schedule");
        columns=image.width()/frameWidth;tiles=columns*(image.height()/frameHeight);ends=new long[frames.size()];long ticks=0;var used=new TreeSet<Integer>();
        for(int i=0;i<frames.size();i++) { var frame=frames.get(i);if(frame.index()>=tiles)throw new IllegalArgumentException("Frame outside sprite sheet");ticks+=frame.ticks();ends[i]=ticks;used.add(frame.index()); }
        cycleTicks=ticks;referencedFrames=List.copyOf(used);
    }
    public AssetId id() { return id; }
    public String pngSha256() { return pngSha256; }
    public Optional<String> metadataSha256() { return metadataSha256; }
    public RgbaImage image() { return image; }
    public Settings settings() { return settings; }
    public int frameWidth() { return frameWidth; }
    public int frameHeight() { return frameHeight; }
    public int tileCount() { return tiles; }
    public boolean animated() { return animated; }
    public boolean interpolate() { return interpolate; }
    public List<Frame> frames() { return frames; }
    public List<Integer> referencedFrames() { return referencedFrames; }
    public long cycleTicks() { return cycleTicks; }
    public Tile tile(int index) { if(index<0||index>=tiles)throw new IndexOutOfBoundsException("Frame outside sprite sheet");return new Tile(index%columns*frameWidth,index/columns*frameHeight,frameWidth,frameHeight); }
    /** Nonnegative integer ticks avoid precision loss at large clocks; partialTick is in [0,1). */
    public Sample sample(long tick,double partialTick) {
        if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid animation clock");
        long time=tick%cycleTicks;int low=0,high=ends.length-1;
        while(low<high) { int middle=(low+high)>>>1;if(time<ends[middle])high=middle;else low=middle+1; }
        long start=low==0?0:ends[low-1];double blend=interpolate?Math.min(Math.nextDown(1.0),(time-start+partialTick)/frames.get(low).ticks()):0;
        return new Sample(low,frames.get(low).index(),frames.get((low+1)%frames.size()).index(),blend);
    }
}
