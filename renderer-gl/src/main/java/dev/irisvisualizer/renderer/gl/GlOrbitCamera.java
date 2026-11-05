package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.Objects;

/** Immutable display-only orbit state. It never moves voxels or changes generation coordinates. */
public record GlOrbitCamera(WorldSnapshot.Position anchor,double centerX,double centerY,double centerZ,double distance,double yaw,double pitch,double fieldOfView,double near,double far) {
    public GlOrbitCamera {
        Objects.requireNonNull(anchor);double[] values={centerX,centerY,centerZ,distance,yaw,pitch,fieldOfView,near,far};for(double value:values)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite orbit camera");
        if(pitch< -89.9||pitch>89.9||distance<0.01||distance<=near||distance>=far||far<=0.02)throw new IllegalArgumentException("Orbit pose exceeds navigation or clipping bounds");yaw=Math.IEEEremainder(yaw,360);
        pose(anchor,centerX,centerY,centerZ,distance,yaw,pitch,fieldOfView,near,far);
    }
    private static GlCamera pose(WorldSnapshot.Position anchor,double x,double y,double z,double distance,double yaw,double pitch,double fov,double near,double far) {
        double a=Math.toRadians(yaw),b=Math.toRadians(pitch),horizontal=Math.cos(b)*distance;
        return new GlCamera(anchor,x+Math.sin(a)*horizontal,y+Math.sin(b)*distance,z+Math.cos(a)*horizontal,x,y,z,fov,near,far);
    }
    public GlCamera camera() { return pose(anchor,centerX,centerY,centerZ,distance,yaw,pitch,fieldOfView,near,far); }
    public GlOrbitCamera orbit(double yawDelta,double pitchDelta) { if(!Double.isFinite(yawDelta)||!Double.isFinite(pitchDelta))throw new IllegalArgumentException("Nonfinite orbit delta");return new GlOrbitCamera(anchor,centerX,centerY,centerZ,distance,yaw+yawDelta,Math.clamp(pitch+pitchDelta,-89.9,89.9),fieldOfView,near,far); }
    public GlOrbitCamera dolly(double factor) {
        if(!Double.isFinite(factor)||factor<=0)throw new IllegalArgumentException("Invalid camera dolly factor");double value=Math.clamp(distance*factor,Math.max(0.01,Math.nextUp(near)),Math.nextDown(far));
        return new GlOrbitCamera(anchor,centerX,centerY,centerZ,value,yaw,pitch,fieldOfView,near,far);
    }
    public GlOrbitCamera pan(double pixelsX,double pixelsY,int viewportHeight) {
        if(!Double.isFinite(pixelsX)||!Double.isFinite(pixelsY)||viewportHeight<1||viewportHeight>4096)throw new IllegalArgumentException("Invalid camera pan");var basis=camera().basis();double scale=2*distance*Math.tan(Math.toRadians(fieldOfView)/2)/viewportHeight;
        return new GlOrbitCamera(anchor,centerX+(-pixelsX*basis[3]+pixelsY*basis[6])*scale,centerY+(-pixelsX*basis[4]+pixelsY*basis[7])*scale,centerZ+(-pixelsX*basis[5]+pixelsY*basis[8])*scale,distance,yaw,pitch,fieldOfView,near,far);
    }
    public GlOrbitCamera rebase(WorldSnapshot.Position next) {
        Objects.requireNonNull(next);return new GlOrbitCamera(next,centerX+Math.subtractExact(anchor.x(),next.x()),centerY+((long)anchor.y()-next.y()),centerZ+Math.subtractExact(anchor.z(),next.z()),distance,yaw,pitch,fieldOfView,near,far);
    }
    /** Caller checks the current picking revision/index receipt before focusing its detached hit. */
    public GlOrbitCamera focus(WorldPick.Hit hit) {
        Objects.requireNonNull(hit);var position=hit.position();
        return new GlOrbitCamera(anchor,Math.subtractExact(position.x(),anchor.x())+hit.localX(),((long)position.y()-anchor.y())+hit.localY(),Math.subtractExact(position.z(),anchor.z())+hit.localZ(),distance,yaw,pitch,fieldOfView,near,far);
    }
    /** Caller checks the current scalar image/volume receipt before adopting a detached hit. */
    public GlOrbitCamera focus(NoisePick.Hit hit) {
        Objects.requireNonNull(hit);
        if(hit.grid().isPresent()) {
            var grid=hit.grid().orElseThrow();int cell=hit.sample().sample(),x=cell%grid.width(),z=cell/grid.width()%grid.depth();long y=cell/((long)grid.width()*grid.depth());double scale=grid.horizontal().blocksPerSample();
            return new GlOrbitCamera(anchor,relativeSample(grid.horizontal().originX(),x*scale,anchor.x(),hit.localX()),((long)grid.vertical().minY()+y-anchor.y())+hit.localY(),relativeSample(grid.horizontal().originZ(),z*scale,anchor.z(),hit.localZ()),distance,yaw,pitch,fieldOfView,near,far);
        }
        var p=hit.sample().point();long x=(long)Math.floor(p.x()),z=(long)Math.floor(p.z());
        return new GlOrbitCamera(anchor,Math.subtractExact(x,anchor.x())+(p.x()-x)+hit.localX(),((long)p.y()-anchor.y())+hit.localY(),Math.subtractExact(z,anchor.z())+(p.z()-z)+hit.localZ(),distance,yaw,pitch,fieldOfView,near,far);
    }
    private static double relativeSample(int origin,double offset,long anchor,double local) {
        long whole=(long)Math.floor(offset);
        return Math.subtractExact(Math.addExact((long)origin,whole),anchor)+(offset-whole)+local;
    }
    /** Fit actual emitted geometry/marker bounds. Empty scenes use their complete configured extent. */
    public static GlOrbitCamera fit(WorldScene scene,double aspect) {
        return fit(scene,aspect,WorldClip.NONE);
    }
    /** Fit conservative section bounds within a geometric display scope, without changing source data. */
    public static GlOrbitCamera fit(WorldScene scene,double aspect,WorldClip clip) {
        Objects.requireNonNull(scene);Objects.requireNonNull(clip);if(!Double.isFinite(aspect)||aspect<=0)throw new IllegalArgumentException("Invalid fit aspect");var extent=scene.snapshot().extent();var anchor=new WorldSnapshot.Position(extent.minX(),extent.minY(),extent.minZ());var scope=clip.relative(anchor);var fit=new FitBounds();boolean drawable=false;
        for(var mesh:scene.sections()) {
            if(mesh.bounds().isEmpty())continue;drawable=true;var bounds=mesh.bounds().orElseThrow();long exactX=Math.subtractExact(mesh.origin().x(),anchor.x()),exactY=(long)mesh.origin().y()-anchor.y(),exactZ=Math.subtractExact(mesh.origin().z(),anchor.z());double x=exactX,y=exactY,z=exactZ;
            if(Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>1048576)throw new GlFailure(GlFailure.Code.LIMIT,"Scene fit exceeds relative camera precision");
            fit.include(x+bounds.min().x(),y+bounds.min().y(),z+bounds.min().z(),x+bounds.max().x(),y+bounds.max().y(),z+bounds.max().z(),scope);
        }
        if(!drawable)fit.include(0,0,0,extent.width(),extent.height(),extent.depth(),scope);
        return fit.camera(anchor,aspect);
    }
    public static GlOrbitCamera fit(NoiseSurface surface,double aspect) {
        return fit(surface,aspect,WorldClip.NONE);
    }
    /** Empty scalar surfaces use their complete display-bin extent, intersected with the supplied scope. */
    public static GlOrbitCamera fit(NoiseSurface surface,double aspect,WorldClip clip) {
        Objects.requireNonNull(surface);Objects.requireNonNull(clip);if(surface.requiresPlaceholder()||!Double.isFinite(aspect)||aspect<=0)throw new IllegalArgumentException("Complete scalar surface and positive aspect required");
        var grid=surface.volume().grid();var anchor=new WorldSnapshot.Position(grid.horizontal().originX(),grid.vertical().minY(),grid.horizontal().originZ());double scale=grid.horizontal().blocksPerSample();
        var b=surface.bounds().orElseGet(()->new NoiseSurface.Bounds(0,0,0,grid.width()*scale,grid.height(),grid.depth()*scale));var fit=new FitBounds();fit.include(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),clip.relative(anchor));return fit.camera(anchor,aspect);
    }
    private static final class FitBounds {
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        void include(double lowX,double lowY,double lowZ,double highX,double highY,double highZ,WorldClip.Bounds clip) {
            lowX=Math.max(lowX,clip.minX());lowY=Math.max(lowY,clip.minY());lowZ=Math.max(lowZ,clip.minZ());highX=Math.min(highX,clip.maxX());highY=Math.min(highY,clip.maxY());highZ=Math.min(highZ,clip.maxZ());
            if(lowX>highX||lowY>highY||lowZ>highZ)return;
            minX=Math.min(minX,lowX);minY=Math.min(minY,lowY);minZ=Math.min(minZ,lowZ);maxX=Math.max(maxX,highX);maxY=Math.max(maxY,highY);maxZ=Math.max(maxZ,highZ);
        }
        GlOrbitCamera camera(WorldSnapshot.Position anchor,double aspect) {
            if(!Double.isFinite(minX))throw new GlFailure(GlFailure.Code.STATE,"Display scope does not intersect the source bounds");
            return fitBounds(anchor,minX,minY,minZ,maxX,maxY,maxZ,aspect);
        }
    }
    private static GlOrbitCamera fitBounds(WorldSnapshot.Position anchor,double minX,double minY,double minZ,double maxX,double maxY,double maxZ,double aspect) {
        double radius=Math.max(0.5,Math.hypot(Math.hypot((maxX-minX)/2,(maxY-minY)/2),(maxZ-minZ)/2)),angle=Math.min(Math.PI/6,Math.atan(Math.tan(Math.PI/6)*aspect)),distance=radius/Math.sin(angle)*1.05,far=Math.max(64,distance+radius*2);
        if(!Double.isFinite(distance)||far>1048576)throw new GlFailure(GlFailure.Code.LIMIT,"Complete scene cannot fit within camera clipping bounds");
        return new GlOrbitCamera(anchor,(minX+maxX)/2,(minY+maxY)/2,(minZ+maxZ)/2,distance,45,30,60,Math.max(0.001,Math.min(0.1,radius/1000)),far);
    }
}
