package dev.irisvisualizer.renderer;

import java.util.Objects;

/** Exact world anchor, relative origin and normalized direction; distances follow that direction. */
public record WorldRay(WorldSnapshot.Position anchor,double x,double y,double z,double dx,double dy,double dz,double minimum,double maximum) {
    public WorldRay {
        Objects.requireNonNull(anchor);double[] values={x,y,z,dx,dy,dz,minimum,maximum};for(double value:values)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite picking ray");
        if(Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>1048576||minimum<0||maximum<minimum)throw new IllegalArgumentException("Picking ray exceeds relative precision or distance bounds");
        double length=Math.hypot(Math.hypot(dx,dy),dz);if(!Double.isFinite(length)||length<1e-12)throw new IllegalArgumentException("Picking direction is too small or large");dx/=length;dy/=length;dz/=length;
    }
}
