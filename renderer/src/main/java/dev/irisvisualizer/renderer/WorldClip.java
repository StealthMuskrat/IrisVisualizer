package dev.irisvisualizer.renderer;

import java.util.*;

/** Closed geometric clipping box in exact world coordinates; it never selects or changes native cells. */
public record WorldClip(Optional<WorldSnapshot.Extent> extent) {
    public static final WorldClip NONE=new WorldClip(Optional.empty());
    private static final Bounds UNBOUNDED=new Bounds(Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY);
    public WorldClip { Objects.requireNonNull(extent); }
    public WorldClip(WorldSnapshot.Extent extent) { this(Optional.of(Objects.requireNonNull(extent))); }
    public boolean enabled() { return extent.isPresent(); }
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public Bounds { if(Double.isNaN(minX)||Double.isNaN(minY)||Double.isNaN(minZ)||Double.isNaN(maxX)||Double.isNaN(maxY)||Double.isNaN(maxZ)||minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("Invalid geometric clip bounds"); }
        public boolean contains(double x,double y,double z) { return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ; }
        public boolean intersects(double lowX,double lowY,double lowZ,double highX,double highY,double highZ) { return lowX<=maxX&&highX>=minX&&lowY<=maxY&&highY>=minY&&lowZ<=maxZ&&highZ>=minZ; }
    }
    /** Subtract exact anchors first, preserving small differences even beyond double's exact integer range. */
    public Bounds relative(WorldSnapshot.Position anchor) {
        Objects.requireNonNull(anchor);if(extent.isEmpty())return UNBOUNDED;var box=extent.orElseThrow();
        return new Bounds(difference(box.minX(),anchor.x()),(long)box.minY()-anchor.y(),difference(box.minZ(),anchor.z()),difference(box.maxX(),anchor.x()),box.maxY()-anchor.y(),difference(box.maxZ(),anchor.z()));
    }
    private static double difference(long value,long origin) {
        try { return Math.subtractExact(value,origin); }
        catch(ArithmeticException overflow) { return value>origin?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY; }
    }
}
