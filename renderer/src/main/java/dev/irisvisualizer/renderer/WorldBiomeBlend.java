package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Capture-local, thread-confined horizontal RGB averaging over an exact detached native biome halo. */
public final class WorldBiomeBlend {
    public record Limits(int radius,long samples) {
        public static final Limits UNBLENDED=new Limits(0,64L*1024*1024);
        public Limits {
            if(radius<0||radius>7||samples<1||samples>256L*1024*1024)throw new IllegalArgumentException("Biome blend radius/sample allowance exceeds renderer bounds");
        }
    }
    @FunctionalInterface public interface Sampler {
        /** Source palette index and exact neighbor position. Alpha, if present, is discarded. */
        int rgb(int biomeIndex,long x,int y,long z);
    }
    public static final int MAX_CACHE_ENTRIES=65536;
    public static long cacheBytes(int entries) {
        if(entries<1||entries>MAX_CACHE_ENTRIES||(entries&(entries-1))!=0)throw new IllegalArgumentException("Biome sample cache requires a bounded power-of-two entry count");
        return (long)entries*2*Integer.BYTES;
    }
    public record CacheStats(long lookups,long hits,long misses) {
        public CacheStats { if(lookups<0||hits<0||hits>lookups||misses!=lookups-hits)throw new IllegalArgumentException("Invalid biome sample cache work"); }
    }
    /** Opt-in memoization: the bound sampler must be deterministic/frozen for this helper's lifetime. */
    public final class MemoizedSampler implements Sampler {
        private final Sampler stable;private final int[] keys,colors;private long lookups,hits,misses;
        private MemoizedSampler(Sampler stable,int entries) { this.stable=stable;keys=new int[entries];colors=new int[entries]; }
        private WorldBiomeBlend owner() { return WorldBiomeBlend.this; }
        public int entries() { return keys.length; }
        public long bytes() { return cacheBytes(keys.length); }
        public CacheStats stats() { return new CacheStats(lookups,hits,misses); }
        @Override public int rgb(int biomeIndex,long x,int y,long z) {
            WorldProblem.check(cancelled);var extent=source.extent();
            if(x<extent.minX()||x>=extent.maxX()||z<extent.minZ()||z>=extent.maxZ()||y<extent.minY()||(long)y>=extent.maxY())throw failure(WorldProblem.Code.EXTENT,"Memoized biome sample is outside its exact captured source");
            int cell=index(x,y,z);if(biomeIndex!=source.biomeIndex(cell))throw failure(WorldProblem.Code.PALETTE,"Memoized biome sample differs from the captured palette identity");
            if(lookups==Long.MAX_VALUE)throw failure(WorldProblem.Code.LIMIT,"Biome sample cache work counter exhausted");lookups++;
            int key=cell+1,slot=Integer.rotateLeft(key*0x9e3779b9,13)&(keys.length-1);
            if(keys[slot]==key) { hits++;return colors[slot]; }
            misses++;int rgb=stable.rgb(biomeIndex,x,y,z);WorldProblem.check(cancelled);
            // Commit only a successful, uncancelled callback. Black and alpha-bearing colors remain exact.
            colors[slot]=rgb;keys[slot]=key;return rgb;
        }
    }
    /** Admit both arrays before allocation; source ownership and fixed object bookkeeping remain external. */
    public MemoizedSampler memoize(Sampler stable,int entries,long allowanceBytes) {
        Objects.requireNonNull(stable);WorldProblem.check(cancelled);long bytes=cacheBytes(entries);
        if(allowanceBytes<0||allowanceBytes>(long)MAX_CACHE_ENTRIES*2*Integer.BYTES)throw new IllegalArgumentException("Biome sample cache byte allowance exceeds its bound");
        if(bytes>allowanceBytes)throw failure(WorldProblem.Code.LIMIT,"Biome sample cache arrays exceed their admitted byte allowance");
        return new MemoizedSampler(stable,entries);
    }
    private final WorldSnapshot source;private final Limits limits;private final BooleanSupplier cancelled;
    private long samples;

    /** Validates complete horizontal coverage and identical overlapping native biome identities. */
    public WorldBiomeBlend(WorldSnapshot target,WorldSnapshot source,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(target);this.source=Objects.requireNonNull(source);this.limits=Objects.requireNonNull(limits);this.cancelled=Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(target.requiresPlaceholder()||source.requiresPlaceholder())throw failure(WorldProblem.Code.PALETTE,"Biome blending requires successful native snapshots");
        var extent=target.extent();var halo=source.extent();
        if(!target.source().equals(source.source())||extent.minY()!=halo.minY()||extent.height()!=halo.height())throw failure(WorldProblem.Code.ASSIGNMENT,"Biome blend halo requires the same native generation and complete height");
        long minX,minZ,maxX,maxZ;
        try { minX=Math.subtractExact(extent.minX(),limits.radius());minZ=Math.subtractExact(extent.minZ(),limits.radius());maxX=Math.addExact(extent.maxX()-1,limits.radius());maxZ=Math.addExact(extent.maxZ()-1,limits.radius()); }
        catch(ArithmeticException overflow) { throw failure(WorldProblem.Code.COORDINATE,"Biome blend footprint exceeds signed world coordinates"); }
        if(minX<halo.minX()||minZ<halo.minZ()||maxX>=halo.maxX()||maxZ>=halo.maxZ())throw failure(WorldProblem.Code.EXTENT,"Biome blending needs a complete captured horizontal halo; missing neighbors are not clamped");
        if(source!=target)for(int cell=0;cell<target.cellCount();cell++) {
            if((cell&4095)==0)WorldProblem.check(cancelled);
            long layer=(long)extent.width()*extent.depth(),x=extent.minX()+cell%extent.width(),z=extent.minZ()+(cell/extent.width())%extent.depth();int y=(int)(extent.minY()+cell/layer);
            if(!target.biome(cell).equals(source.biome(index(x,y,z))))throw failure(WorldProblem.Code.PALETTE,"Biome blend halo disagrees with an overlapping native biome identity");
        }
        WorldProblem.check(cancelled);
    }
    public WorldSnapshot source() { return source; }
    public int radius() { return limits.radius(); }
    /** Reserved samples, including the entire footprint of any cancelled/failed callback sequence. */
    public long samples() { return samples; }

    /** Floor each RGB channel independently. All footprint/allowance checks precede callbacks. */
    public int rgb(WorldSnapshot.Position center,Sampler sampler) {
        Objects.requireNonNull(center);Objects.requireNonNull(sampler);WorldProblem.check(cancelled);
        if(sampler instanceof MemoizedSampler memo&&memo.owner()!=this)throw failure(WorldProblem.Code.ASSIGNMENT,"Memoized biome sampler belongs to another capture helper");
        int radius=limits.radius(),diameter=radius*2+1,count=diameter*diameter;long minX,minZ,maxX,maxZ;
        try { minX=Math.subtractExact(center.x(),radius);minZ=Math.subtractExact(center.z(),radius);maxX=Math.addExact(center.x(),radius);maxZ=Math.addExact(center.z(),radius); }
        catch(ArithmeticException overflow) { throw failure(WorldProblem.Code.COORDINATE,"Biome blend sample footprint exceeds signed world coordinates"); }
        var extent=source.extent();
        if(minX<extent.minX()||maxX>=extent.maxX()||minZ<extent.minZ()||maxZ>=extent.maxZ()||center.y()<extent.minY()||(long)center.y()>=extent.maxY())throw failure(WorldProblem.Code.EXTENT,"Biome blend sample needs uncaptured neighbors or height");
        if(count>limits.samples()-samples)throw failure(WorldProblem.Code.LIMIT,"Biome blend sample allowance reached");
        samples+=count;
        int red=0,green=0,blue=0;
        // Offset loops also work when a valid source footprint ends at Long.MAX_VALUE-1.
        for(int dz=0;dz<diameter;dz++)for(int dx=0;dx<diameter;dx++) {
            WorldProblem.check(cancelled);long x=minX+dx,z=minZ+dz;
            int rgb=sampler.rgb(source.biomeIndex(index(x,center.y(),z)),x,center.y(),z);
            red+=(rgb>>>16)&255;green+=(rgb>>>8)&255;blue+=rgb&255;
        }
        WorldProblem.check(cancelled);return (red/count)<<16|(green/count)<<8|blue/count;
    }
    private int index(long x,int y,long z) {
        var extent=source.extent();return Math.toIntExact((((long)y-extent.minY())*extent.depth()+(z-extent.minZ()))*extent.width()+(x-extent.minX()));
    }
    private static WorldCapture.ReadFailure failure(WorldProblem.Code code,String reason) { return new WorldCapture.ReadFailure(new WorldProblem(code,Optional.empty(),reason)); }
}
