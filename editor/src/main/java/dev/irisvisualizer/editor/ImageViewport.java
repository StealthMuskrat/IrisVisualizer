package dev.irisvisualizer.editor;

/** Read-only image coordinates in logical canvas units. No image bytes, GPU objects or workspace writes. */
public final class ImageViewport {
    public static final double MIN_SCALE=1.0/4096, MAX_SCALE=32, MAX_CANVAS=1_000_000;
    public record Pixel(int x,int y) {}
    /** Visible canvas rectangle and texture UVs; source image rows run from top to bottom. */
    public record Geometry(double left,double top,double right,double bottom,double u0,double v0,double u1,double v1,double scale) {
        public double width() { return right-left; }
        public double height() { return bottom-top; }
    }
    private final int width,height;
    private double canvasWidth=1,canvasHeight=1,centerX,centerY,scale=1;
    private boolean fit=true;
    public ImageViewport(int width,int height) {
        try { AssetImage.checkSize(width,height); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException(invalid.getMessage(),invalid); }
        this.width=width;this.height=height;centerX=width/2.0;centerY=height/2.0;
    }
    public boolean fitting() { return fit; }
    public double scale() { return fit ? fitScale() : scale; }
    public double centerX() { return centerX; }
    public double centerY() { return centerY; }
    private double fitScale() { return Math.max(MIN_SCALE,Math.min(1,Math.min(canvasWidth/width,canvasHeight/height))); }
    private static double finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Image coordinates must be finite");return value; }
    public void resize(double width,double height) {
        finite(width);finite(height);
        if (width<1 || height<1 || width>MAX_CANVAS || height>MAX_CANVAS) throw new IllegalArgumentException("Image canvas must be 1..1,000,000 logical units per edge");
        canvasWidth=width;canvasHeight=height;clampCenter();
    }
    public void fit() { fit=true;centerX=width/2.0;centerY=height/2.0; }
    public void actualSize() { fit=false;scale=1;clampCenter(); }
    public void zoom(double factor) { zoom(factor,canvasWidth/2,canvasHeight/2); }
    public void zoom(double factor,double anchorX,double anchorY) {
        finite(factor);finite(anchorX);finite(anchorY);
        if (factor<=0 || anchorX<0 || anchorX>canvasWidth || anchorY<0 || anchorY>canvasHeight) throw new IllegalArgumentException("Use a positive zoom factor and an anchor inside the canvas");
        double previous=scale(),next=Math.max(MIN_SCALE,Math.min(MAX_SCALE,previous*factor));
        double sourceX=centerX+(anchorX-canvasWidth/2)/previous,sourceY=centerY+(anchorY-canvasHeight/2)/previous;
        centerX=sourceX-(anchorX-canvasWidth/2)/next;centerY=sourceY-(anchorY-canvasHeight/2)/next;
        scale=next;fit=false;clampCenter();
    }
    public void pan(double deltaX,double deltaY) {
        finite(deltaX);finite(deltaY);scale=scale();fit=false;
        centerX-=deltaX/scale;centerY-=deltaY/scale;clampCenter();
    }
    private static double clamp(double value,double low,double high) { return Math.max(low,Math.min(high,value)); }
    private void clampCenter() {
        if (fit) { centerX=width/2.0;centerY=height/2.0;return; }
        double halfWidth=canvasWidth/(2*scale),halfHeight=canvasHeight/(2*scale);
        centerX=halfWidth>=width/2.0 ? width/2.0 : clamp(centerX,halfWidth,width-halfWidth);
        centerY=halfHeight>=height/2.0 ? height/2.0 : clamp(centerY,halfHeight,height-halfHeight);
    }
    public Geometry geometry() {
        double s=scale(),drawWidth=width*s,drawHeight=height*s;
        double x=canvasWidth/2-centerX*s,y=canvasHeight/2-centerY*s;
        double left=Math.max(0,x),top=Math.max(0,y),right=Math.min(canvasWidth,x+drawWidth),bottom=Math.min(canvasHeight,y+drawHeight);
        return new Geometry(left,top,right,bottom,clamp((left-x)/drawWidth,0,1),clamp((top-y)/drawHeight,0,1),clamp((right-x)/drawWidth,0,1),clamp((bottom-y)/drawHeight,0,1),s);
    }
    /** Null in letterboxing/outside the canvas; pixels use top-left origin and exclusive right/bottom edges. */
    public Pixel pixelAt(double x,double y) {
        finite(x);finite(y);var g=geometry();
        if (x<g.left() || x>=g.right() || y<g.top() || y>=g.bottom()) return null;
        double sourceX=centerX+(x-canvasWidth/2)/g.scale(),sourceY=centerY+(y-canvasHeight/2)/g.scale();
        // Geometry already established containment; clamp only floating-point roundoff at source edges.
        return new Pixel((int)clamp(Math.floor(sourceX),0,width-1),(int)clamp(Math.floor(sourceY),0,height-1));
    }
}
