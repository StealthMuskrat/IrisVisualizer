package dev.irisvisualizer.renderer;

import java.util.*;

/** Explicit display-only straight RGBA8 gradient; native values remain unmodified. */
public final class MapRamp {
    public record Stop(double position,int rgba) { public Stop { if(!Double.isFinite(position)||position<0||position>1)throw new IllegalArgumentException("Invalid gradient position"); } }
    public static final MapRamp GRAYSCALE=new MapRamp(List.of(new Stop(0,0x000000ff),new Stop(1,0xffffffff)));
    private final List<Stop> stops;
    public MapRamp(List<Stop> stops) {
        Objects.requireNonNull(stops);if(stops.size()<2||stops.size()>64)throw new IllegalArgumentException("Use two through 64 gradient stops");this.stops=List.copyOf(stops);
        double previous=-1;for(var stop:this.stops) { if(stop.position()<=previous)throw new IllegalArgumentException("Gradient positions must strictly increase");previous=stop.position(); }
        if(this.stops.getFirst().position()!=0||this.stops.getLast().position()!=1)throw new IllegalArgumentException("Gradient endpoints must be zero and one");
    }
    public List<Stop> stops() { return stops; }
    @Override public boolean equals(Object other) { return other instanceof MapRamp ramp&&stops.equals(ramp.stops); }
    @Override public int hashCode() { return stops.hashCode(); }
    public int rgba(double position) {
        if(!Double.isFinite(position))throw new IllegalArgumentException("Nonfinite gradient lookup");if(position<=0)return stops.getFirst().rgba();if(position>=1)return stops.getLast().rgba();
        for(int i=1;i<stops.size();i++) { var right=stops.get(i);if(position>right.position())continue;var left=stops.get(i-1);double fraction=(position-left.position())/(right.position()-left.position());int result=0;
            for(int shift=24;shift>=0;shift-=8) { int a=left.rgba()>>>shift&255,b=right.rgba()>>>shift&255;result|=(int)Math.round(a+(b-a)*fraction)<<shift; }return result;
        }
        throw new IllegalStateException("Gradient interval missing");
    }
}
