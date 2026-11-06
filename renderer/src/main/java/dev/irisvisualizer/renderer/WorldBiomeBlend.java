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
