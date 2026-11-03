package dev.irisvisualizer.renderer;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Top-left origin, unpremultiplied source samples in RGBA byte order. No color conversion. */
public final class RgbaImage {
    private final int width,height;private final byte[] pixels;
    private RgbaImage(int width,int height,byte[] owned) {
        if(width<1||height<1||(long)width*height*4!=owned.length)throw new IllegalArgumentException("Invalid RGBA dimensions");
        this.width=width;this.height=height;pixels=owned;
    }
    static RgbaImage owned(int width,int height,byte[] owned) { return new RgbaImage(width,height,owned); }
    /** Sequential single-owner construction; source rows are copied and no writable image array escapes. */
    public static final class Builder {
        private final int width,height,bytes;private byte[] pixels;private int rows;
        private Builder(int width,int height,long limit) {
            long bytes=(long)width*height*4;
            if(width<1||height<1||width>8192||height>8192||limit<0||limit>64L*1024*1024||bytes>limit)throw new IllegalArgumentException("RGBA builder exceeds its bounded image payload");
            this.width=width;this.height=height;this.bytes=(int)bytes;pixels=new byte[this.bytes];
        }
        public int completedRows() { return rows; }
        public int bytes() { return bytes; }
        /** Appends complete tightly packed rows; bottomToTop reverses source rows without changing source cursors. */
        public void appendRows(ByteBuffer source,int count,boolean bottomToTop) {
            Objects.requireNonNull(source);if(pixels==null)throw new IllegalStateException("RGBA builder is sealed");int rowBytes=width*4;
            if(count<1||count>height-rows||(long)count*rowBytes!=source.remaining())throw new IllegalArgumentException("RGBA rows do not cover a complete bounded append");
            var input=source.duplicate();int start=input.position();
            for(int row=0;row<count;row++) { input.position(start+(bottomToTop?count-row-1:row)*rowBytes);input.get(pixels,(rows+row)*rowBytes,rowBytes); }rows+=count;
        }
        /** Transfers the private array once; neither an incomplete builder nor later appends can expose/mutate it. */
        public RgbaImage finish() { if(pixels==null||rows!=height)throw new IllegalStateException("RGBA image is incomplete or already sealed");var result=new RgbaImage(width,height,pixels);pixels=null;return result; }
    }
    public static Builder builder(int width,int height,long maximumBytes) { return new Builder(width,height,maximumBytes); }
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
