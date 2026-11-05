package dev.irisvisualizer.renderer;

import java.util.Optional;

/** Display-only top-left image transform. Panning/zooming never resamples or edits native data. */
public record MapView(int width,int height,double left,double top,double pixelsPerSample) {
    public record Pixel(int x,int z,int index) {}
    public MapView {
        if(width<1||width>8192||height<1||height>8192||!Double.isFinite(left)||!Double.isFinite(top)||Math.abs(left)>1e12||Math.abs(top)>1e12||!Double.isFinite(pixelsPerSample)||pixelsPerSample<1e-6||pixelsPerSample>1e6)throw new IllegalArgumentException("Map display transform exceeds finite bounds");
        if(left+width*pixelsPerSample<=left||top+height*pixelsPerSample<=top)throw new IllegalArgumentException("Map display extent is below coordinate precision");
    }
    public static MapView fit(MapSamples.Grid grid,double left,double top,double viewportWidth,double viewportHeight) {
        return fit(grid.width(),grid.height(),left,top,viewportWidth,viewportHeight);
    }
    /** Original images use their actual dimensions; map capture retains its independent grid limits. */
    public static MapView fit(int width,int height,double left,double top,double viewportWidth,double viewportHeight) {
        if(width<1||width>8192||height<1||height>8192)throw new IllegalArgumentException("Invalid image dimensions");
        if(!Double.isFinite(viewportWidth)||!Double.isFinite(viewportHeight)||viewportWidth<=0||viewportHeight<=0)throw new IllegalArgumentException("Invalid map viewport");double scale=Math.min(viewportWidth/width,viewportHeight/height);
        return new MapView(width,height,left+(viewportWidth-width*scale)/2,top+(viewportHeight-height*scale)/2,scale);
    }
    public double right() { return left+width*pixelsPerSample; }
    public double bottom() { return top+height*pixelsPerSample; }
    public MapView pan(double deltaX,double deltaY) { if(deltaX==0&&deltaY==0)return this;return new MapView(width,height,left+deltaX,top+deltaY,pixelsPerSample); }
    public MapView zoomAt(double factor,double screenX,double screenY) {
        if(!Double.isFinite(factor)||factor<=0||!Double.isFinite(screenX)||!Double.isFinite(screenY))throw new IllegalArgumentException("Invalid map zoom anchor");
        if(factor==1)return this;
        return new MapView(width,height,zoomOrigin(left,screenX,factor),zoomOrigin(top,screenY,factor),pixelsPerSample*factor);
    }
    private static double zoomOrigin(double origin,double anchor,double factor) {
        double delta=anchor-origin;
        // Around unity, apply the small displacement to the origin instead of cancelling viewport-sized values.
        return factor>=0.5&&factor<=2?Math.fma(1-factor,delta,origin):Math.fma(-factor,delta,anchor);
    }
    public Optional<Pixel> pixelAt(double screenX,double screenY) {
        if(!Double.isFinite(screenX)||!Double.isFinite(screenY)||screenX<left||screenX>=right()||screenY<top||screenY>=bottom())return Optional.empty();
        int x=sampleAt(screenX,left,width),z=sampleAt(screenY,top,height);return Optional.of(new Pixel(x,z,z*width+x));
    }
    /** Correct a rounded inverse against the displayed edges; no pixel scan or per-query scratch is needed. */
    private int sampleAt(double coordinate,double origin,int count) {
        int sample=Math.clamp((int)Math.floor((coordinate-origin)/pixelsPerSample),0,count-1);
        double end=origin+count*pixelsPerSample;
        // When adjacent edges are distinguishable, inverse rounding can cross at most one boundary.
        // Coarser screen coordinates retain their existing inverse lookup rather than selecting a collapsed edge.
        if(pixelsPerSample>=2*Math.ulp(Math.max(Math.abs(origin),Math.abs(end)))) {
            if(sample>0&&coordinate<origin+sample*pixelsPerSample)sample--;
            else if(sample+1<count&&coordinate>=origin+(sample+1)*pixelsPerSample)sample++;
        }
        return sample;
    }
}
