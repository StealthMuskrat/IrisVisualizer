package dev.irisvisualizer.renderer;

import java.nio.ByteBuffer;

/** Top-left origin, unpremultiplied source samples in RGBA byte order. No color conversion. */
public final class RgbaImage {
    private final int width,height;private final byte[] pixels;
    private RgbaImage(int width,int height,byte[] owned) {
        if(width<1||height<1||(long)width*height*4!=owned.length)throw new IllegalArgumentException("Invalid RGBA dimensions");
        this.width=width;this.height=height;pixels=owned;
    }
    static RgbaImage owned(int width,int height,byte[] owned) { return new RgbaImage(width,height,owned); }
    public int width() { return width; }
    public int height() { return height; }
    public int bytes() { return pixels.length; }
    private int offset(int x,int y) {
        if(x<0||x>=width||y<0||y>=height)throw new IndexOutOfBoundsException("Pixel outside image");return (y*width+x)*4;
    }
    /** Packed RRGGBBAA, independent of the host byte order. */
    public int rgba(int x,int y) { int at=offset(x,y);return (pixels[at]&255)<<24|(pixels[at+1]&255)<<16|(pixels[at+2]&255)<<8|pixels[at+3]&255; }
    public int alpha(int x,int y) { return pixels[offset(x,y)+3]&255; }
    public ByteBuffer pixels() { return ByteBuffer.wrap(pixels).asReadOnlyBuffer(); }
    public byte[] copyPixels() { return pixels.clone(); }
}
