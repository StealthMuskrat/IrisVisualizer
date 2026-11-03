package dev.irisvisualizer.renderer;

import java.util.Optional;

/** Display-only top-left image transform. Panning/zooming never resamples or edits native data. */
public record MapView(int width,int height,double left,double top,double pixelsPerSample) {
    public record Pixel(int x,int z,int index) {}
    public MapView {
        if(width<1||width>4096||height<1||height>4096||!Double.isFinite(left)||!Double.isFinite(top)||Math.abs(left)>1e12||Math.abs(top)>1e12||!Double.isFinite(pixelsPerSample)||pixelsPerSample<1e-6||pixelsPerSample>1e6)throw new IllegalArgumentException("Map display transform exceeds finite bounds");
        if(left+width*pixelsPerSample<=left||top+height*pixelsPerSample<=top)throw new IllegalArgumentException("Map display extent is below coordinate precision");
    }
    public static MapView fit(MapSamples.Grid grid,double left,double top,double viewportWidth,double viewportHeight) {
        if(!Double.isFinite(viewportWidth)||!Double.isFinite(viewportHeight)||viewportWidth<=0||viewportHeight<=0)throw new IllegalArgumentException("Invalid map viewport");double scale=Math.min(viewportWidth/grid.width(),viewportHeight/grid.height());
        return new MapView(grid.width(),grid.height(),left+(viewportWidth-grid.width()*scale)/2,top+(viewportHeight-grid.height()*scale)/2,scale);
    }
    public double right() { return left+width*pixelsPerSample; }
    public double bottom() { return top+height*pixelsPerSample; }
    public MapView pan(double deltaX,double deltaY) { return new MapView(width,height,left+deltaX,top+deltaY,pixelsPerSample); }
    public MapView zoomAt(double factor,double screenX,double screenY) {
        if(!Double.isFinite(factor)||factor<=0||!Double.isFinite(screenX)||!Double.isFinite(screenY))throw new IllegalArgumentException("Invalid map zoom anchor");
        return new MapView(width,height,screenX-(screenX-left)*factor,screenY-(screenY-top)*factor,pixelsPerSample*factor);
    }
    public Optional<Pixel> pixelAt(double screenX,double screenY) {
        if(!Double.isFinite(screenX)||!Double.isFinite(screenY)||screenX<left||screenX>=right()||screenY<top||screenY>=bottom())return Optional.empty();double x=Math.floor((screenX-left)/pixelsPerSample),z=Math.floor((screenY-top)/pixelsPerSample);
        if(x<0||x>=width||z<0||z>=height)return Optional.empty();return Optional.of(new Pixel((int)x,(int)z,(int)z*width+(int)x));
    }
}
