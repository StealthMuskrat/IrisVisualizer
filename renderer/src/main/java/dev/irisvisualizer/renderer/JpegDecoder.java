package dev.irisvisualizer.renderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStreamImpl;

/** Original-image JPEG only. Texture PNGs never use ImageIO or installed third-party readers. */
final class JpegDecoder {
    private JpegDecoder() {}
    static final class Failure extends RuntimeException {
        final SourceImage.Code code;
        Failure(SourceImage.Code code,String reason) { super(reason);this.code=code; }
    }
    private static Failure format(String reason) { return new Failure(SourceImage.Code.FORMAT,reason); }
    private static Failure limit(String reason) { return new Failure(SourceImage.Code.LIMIT,reason); }
    private static void require(boolean valid,String reason) { if(!valid)throw format(reason); }
    private record Header(int width,int height,int maxH,int maxV) {
        long pixels() { return (long)width*height; }
        long workingBytes(int encoded) {
            long paddedWidth=((width+maxH*8-1)/(maxH*8))*(long)maxH*8;
            long paddedHeight=((height+maxV*8-1)/(maxV*8))*(long)maxV*8;
            // Encoded ownership, BGR destination, RGBA result, conversion row, and conservative codec reserve.
            // Twelve bytes per padded pixel cover two complete three-component 16-bit coefficient sets.
            return encoded+7*pixels()+4L*width+12*paddedWidth*paddedHeight+1024*1024;
        }
    }
    private static int u8(byte[] bytes,int at) { return bytes[at]&255; }
    private static int u16(byte[] bytes,int at) { return (u8(bytes,at)<<8)|u8(bytes,at+1); }
    private static boolean prefix(byte[] bytes,int at,int end,String text) {
        if(end-at<text.length())return false;
        for(int i=0;i<text.length();i++)if(u8(bytes,at+i)!=text.charAt(i))return false;
        return true;
    }
    /** No lists, embedded metadata, raster or codec are allocated during the bounded marker walk. */
    private static Header inspect(byte[] bytes,SourceImage.Limits limits,BooleanSupplier cancelled) {
        require(bytes.length>=4&&u8(bytes,0)==255&&u8(bytes,1)==216,"Invalid JPEG signature");
        Header header=null;int components=0,ids=0,quantSelectors=0,quantTables=0,dcTables=0,acTables=0,scanned=0,at=2,markers=1,scans=0;boolean adobe=false,progressive=false;
        while(at<bytes.length) {
            WorldProblem.check(cancelled);
            if(++markers>4096)throw limit("JPEG marker-count allowance exceeded");
            require(u8(bytes,at++)==255,"JPEG marker prefix is missing");
            while(at<bytes.length&&u8(bytes,at)==255) { if((at&16383)==0)WorldProblem.check(cancelled);at++; }
            require(at<bytes.length,"Truncated JPEG marker");int marker=u8(bytes,at++);
            if(marker==217) {
                require(header!=null&&scans>0&&scanned==(1<<components)-1,"JPEG has no complete component scan coverage");
                require(at==bytes.length,"JPEG trailing or concatenated image data is unsupported");return header;
            }
            require(marker!=0&&marker!=216&&marker!=1&&(marker<208||marker>215),"Unexpected standalone JPEG marker");
            require(bytes.length-at>=2,"Truncated JPEG segment length");int size=u16(bytes,at);
            require(size>=2&&size<=bytes.length-at,"Truncated or invalid JPEG segment");int start=at+2,end=at+size;
            switch(marker) {
                case 192,193,194 -> {
                    require(header==null&&scans==0&&end-start>=6,"Duplicate or malformed JPEG frame");
                    require(u8(bytes,start)==8,"Only eight-bit JPEG samples are supported");
                    int height=u16(bytes,start+1),width=u16(bytes,start+3);components=u8(bytes,start+5);
                    require(components==1||components==3,"Only grayscale and three-component JPEG images are supported");
                    require(end-start==6+3*components,"Invalid JPEG component directory");
                    if(width<1||height<1||width>limits.dimension()||height>limits.dimension()||
                            (long)width*height>limits.pixels()||(long)width*height*4>limits.rgbaBytes())
                        throw limit("JPEG dimensions or complete RGBA payload exceed their allowance");
                    int maxH=0,maxV=0,units=0;ids=0;progressive=marker==194;
                    for(int i=0;i<components;i++) {
                        int id=u8(bytes,start+6+i*3),sampling=u8(bytes,start+7+i*3),h=sampling>>>4,v=sampling&15;
                        for(int prior=0;prior<i;prior++)require(id!=((ids>>>(prior*8))&255),"Duplicate JPEG component identity");
                        ids|=id<<(i*8);
                        require(h>=1&&h<=4&&v>=1&&v<=4&&u8(bytes,start+8+i*3)<=3,"Unsupported JPEG sampling or quantization selector");
                        quantSelectors|=u8(bytes,start+8+i*3)<<(i*2);
                        maxH=Math.max(maxH,h);maxV=Math.max(maxV,v);units+=h*v;
                    }
                    require(units<=10,"JPEG MCU sampling exceeds the supported codec bound");
                    if(components==3)require(ids==0x030201||ids==0x424752,"Unknown JPEG color-component identities");
                    header=new Header(width,height,maxH,maxV);
                    if(header.workingBytes(bytes.length)>limits.workingBytes())throw limit("JPEG raster, coefficients and complete output exceed the decode working allowance");
                }
                case 218 -> {
                    require(header!=null&&end-start>=4,"JPEG scan precedes its frame");
                    if(++scans>256)throw limit("JPEG scan-count allowance exceeded");
                    int count=u8(bytes,start);require(count>=1&&count<=components&&end-start==4+2*count,"Invalid JPEG scan component directory");
                    int spectral=u8(bytes,end-3),successive=u8(bytes,end-1)>>>4;
                    int seen=0;
                    for(int i=0;i<count;i++) {
                        int id=u8(bytes,start+1+i*2),component=-1,selector=u8(bytes,start+2+i*2);
                        for(int j=0;j<components;j++)if(((ids>>>(j*8))&255)==id)component=j;
                        require(component>=0&&(seen&(1<<component))==0,"Unknown or duplicate JPEG scan component");
                        require((selector>>>4)<=3&&(selector&15)<=3,"Unsupported JPEG Huffman table selector");
                        require((quantTables&(1<<((quantSelectors>>>(component*2))&3)))!=0,"JPEG scan references a missing quantization table");
                        if(!progressive||spectral==0&&successive==0)require((dcTables&(1<<(selector>>>4)))!=0,"JPEG scan references a missing DC Huffman table");
                        if(!progressive||spectral>0)require((acTables&(1<<(selector&15)))!=0,"JPEG scan references a missing AC Huffman table");
                        seen|=1<<component;
                    }
                    scanned|=seen;at=end;int checked=at;
                    // Walk stuffed bytes/restarts without interpreting compressed samples or repairing a missing EOI.
                    while(at<bytes.length) {
                        if(at-checked>=16384) { WorldProblem.check(cancelled);checked=at; }
                        if(u8(bytes,at)!=255) { at++;continue; }
                        int mark=at++;while(at<bytes.length&&u8(bytes,at)==255) { if((at&16383)==0)WorldProblem.check(cancelled);at++; }
                        require(at<bytes.length,"Truncated JPEG entropy marker");int code=u8(bytes,at);
                        if(code==0||(code>=208&&code<=215)) { at++;continue; }
                        at=mark;break;
                    }
                    continue;
                }
                case 219 -> {
                    require(start<end,"Empty JPEG quantization directory");
                    for(int table=start;table<end;) {
                        WorldProblem.check(cancelled);int selector=u8(bytes,table++),precision=selector>>>4,id=selector&15;
                        require(precision<=1&&id<=3,"Unsupported JPEG quantization table");int count=64*(precision+1);
                        require(count<=end-table,"Truncated JPEG quantization table");
                        for(int value=table;value<table+count;value+=precision+1)
                            require((precision==0?u8(bytes,value):u16(bytes,value))>0,"JPEG quantization entries must be positive");
                        quantTables|=1<<id;table+=count;
                    }
                }
                case 196 -> {
                    require(start<end,"Empty JPEG Huffman directory");
                    for(int table=start;table<end;) {
                        WorldProblem.check(cancelled);require(end-table>=17,"Truncated JPEG Huffman directory");
                        int selector=u8(bytes,table++),kind=selector>>>4,id=selector&15,symbols=0,available=1;
                        require(kind<=1&&id<=3,"Unsupported JPEG Huffman table");
                        for(int i=0;i<16;i++) { int count=u8(bytes,table++);symbols+=count;available=available*2-count;require(available>=0,"Oversubscribed JPEG Huffman tree"); }
                        require(symbols>0&&symbols<=256&&symbols<=end-table,"Invalid JPEG Huffman symbol count");
                        if(kind==0)dcTables|=1<<id;else acTables|=1<<id;table+=symbols;
                    }
                }
                case 221 -> require(end-start==2,"Invalid JPEG restart interval");
                default -> {
                    require(marker>=224&&marker<=239||marker==254,"Unsupported JPEG coding process or marker");
                    if(marker==226&&prefix(bytes,start,end,"ICC_PROFILE"))throw format("JPEG ICC color profiles require an unsupported color-management path");
                    if(marker==238&&prefix(bytes,start,end,"Adobe")) {
                        require(!adobe&&scans==0&&end-start==12,"Duplicate, late or malformed JPEG Adobe color declaration");adobe=true;
                        require(u8(bytes,start+11)<=1,"JPEG YCCK color conversion is unsupported");
                    }
                }
            }
            at=end;
        }
        throw format("JPEG is missing its final EOI marker; partial pixels were discarded");
    }
    /** Only instantiate the standard java.desktop provider; no format sniffing through arbitrary readers. */
    private static ImageReader reader() throws IOException {
        var providers=IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class,true);
        while(providers.hasNext()) {
            var provider=providers.next();
            if(provider.getClass().getName().equals("com.sun.imageio.plugins.jpeg.JPEGImageReaderSpi")&&
                    provider.getClass().getModule()==ImageReader.class.getModule())return provider.createReaderInstance();
        }
        throw new Failure(SourceImage.Code.WORKER,"The installed Java runtime has no supported standard JPEG reader");
    }
    private static final class Progress implements IIOReadProgressListener {
        final BooleanSupplier cancelled;boolean aborted;
        Progress(BooleanSupplier cancelled) { this.cancelled=cancelled; }
        private void check(ImageReader source) { if(cancelled.getAsBoolean())source.abort(); }
        @Override public void sequenceStarted(ImageReader source,int minimum) { check(source); }
        @Override public void sequenceComplete(ImageReader source) { check(source); }
        @Override public void imageStarted(ImageReader source,int image) { check(source); }
        @Override public void imageProgress(ImageReader source,float percentage) { check(source); }
        @Override public void imageComplete(ImageReader source) { check(source); }
        @Override public void thumbnailStarted(ImageReader source,int image,int thumbnail) { check(source); }
        @Override public void thumbnailProgress(ImageReader source,float percentage) { check(source); }
        @Override public void thumbnailComplete(ImageReader source) { check(source); }
        @Override public void readAborted(ImageReader source) { aborted=true; }
    }
    /** Direct read-only access to the private encoded array; no MemoryCache/FileCache stream or temporary file. */
    private static final class Input extends ImageInputStreamImpl {
        private final byte[] bytes;private final BooleanSupplier cancelled;
        Input(byte[] bytes,BooleanSupplier cancelled) { this.bytes=bytes;this.cancelled=cancelled; }
        @Override public int read() throws IOException {
            checkClosed();WorldProblem.check(cancelled);bitOffset=0;
            return streamPos>=bytes.length?-1:bytes[(int)streamPos++]&255;
        }
        @Override public int read(byte[] target,int offset,int length) throws IOException {
            checkClosed();Objects.checkFromIndexSize(offset,length,target.length);WorldProblem.check(cancelled);bitOffset=0;
            if(length==0)return 0;if(streamPos>=bytes.length)return -1;
            int count=(int)Math.min(Math.min(length,65536),bytes.length-streamPos);
            System.arraycopy(bytes,(int)streamPos,target,offset,count);streamPos+=count;return count;
        }
        @Override public long length() { return bytes.length; }
    }
    static RgbaImage decode(byte[] bytes,SourceImage.Limits limits,BooleanSupplier cancelled) {
        WorldProblem.check(cancelled);var header=inspect(bytes,limits,cancelled);WorldProblem.check(cancelled);
        try(var input=new Input(bytes,cancelled)) {
            var reader=reader();Throwable failure=null;
            try {
                var progress=new Progress(cancelled);var warning=new boolean[1];
                reader.addIIOReadProgressListener(progress);
                reader.addIIOReadWarningListener((source,message)->warning[0]=true);
                reader.setInput(input,true,true);
                require(reader.getWidth(0)==header.width()&&reader.getHeight(0)==header.height(),"JPEG codec dimensions differ from validated source");
                WorldProblem.check(cancelled);
                var destination=new BufferedImage(header.width(),header.height(),BufferedImage.TYPE_3BYTE_BGR);
                var parameters=reader.getDefaultReadParam();parameters.setDestination(destination);
                var decoded=reader.read(0,parameters);WorldProblem.check(cancelled);
                require(!progress.aborted&&!warning[0]&&decoded==destination,"JPEG decoder reported incomplete, repaired or unsupported image data");
                byte[] rgba=new byte[Math.toIntExact(header.pixels()*4)];int[] row=new int[header.width()];int at=0;
                for(int y=0;y<header.height();y++) {
                    WorldProblem.check(cancelled);decoded.getRGB(0,y,header.width(),1,row,0,header.width());
                    for(int rgb:row) { rgba[at++]=(byte)(rgb>>>16);rgba[at++]=(byte)(rgb>>>8);rgba[at++]=(byte)rgb;rgba[at++]=(byte)255; }
                }
                WorldProblem.check(cancelled);return RgbaImage.owned(header.width(),header.height(),rgba);
            }catch(IOException|RuntimeException|Error error) { failure=error;throw error; }
            finally {
                try { reader.dispose(); }
                catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
            }
        }catch(IOException failure) {
            WorldProblem.check(cancelled);throw format("JPEG codec rejected the encoded image; no partial pixels were published");
        }
    }
}
