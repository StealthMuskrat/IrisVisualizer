package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Bounded PNG source-sample decoder. No ImageIO plugins, profile conversion or APNG playback. */
final class PngDecoder {
    private record Segment(int offset,int bytes) {}
    private static final int[] X={0,4,0,2,0,1,0},Y={0,0,4,0,2,0,1},DX={8,8,4,4,2,2,1},DY={8,8,8,4,4,2,2};
    private final AssetId id;private final byte[] source;private final TextureMaterials.Limits limits;private final BooleanSupplier cancelled;
    private final List<Segment> data=new ArrayList<>();
    private int width,height,depth,type,channels,interlace;private byte[] palette,transparency;
    private PngDecoder(AssetId id,byte[] source,TextureMaterials.Limits limits,BooleanSupplier cancelled) { this.id=id;this.source=source;this.limits=limits;this.cancelled=cancelled; }
    static RgbaImage decode(AssetId id,byte[] source,TextureMaterials.Limits limits,long remainingRgba,BooleanSupplier cancelled) {
        var decoder=new PngDecoder(id,source,limits,cancelled);decoder.directory(remainingRgba);return decoder.image();
    }
    private void check() { if(cancelled.getAsBoolean())throw new CancellationException("PNG decoding cancelled"); }
    private ModelFields.Failure fail(ModelPlan.Code code,String pointer,String reason) { return TextureMetadata.failure(code,ClientAssetSnapshot.Kind.TEXTURE,id,pointer,reason); }
    private void require(boolean valid,String pointer,String reason) { if(!valid)throw fail(ModelPlan.Code.TEXTURE_FORMAT,pointer,reason); }
    private long u32(int at) { return (long)(source[at]&255)<<24|(long)(source[at+1]&255)<<16|(long)(source[at+2]&255)<<8|source[at+3]&255; }
    private static boolean letter(int c) { return c>='A'&&c<='Z'||c>='a'&&c<='z'; }
    private void directory(long remainingRgba) {
        check();byte[] signature={(byte)137,80,78,71,13,10,26,10};
        require(source.length>=8,"","Truncated PNG signature");for(int i=0;i<8;i++)require(source[i]==signature[i],"","Invalid PNG signature");
        boolean header=false,seenData=false,closedData=false,end=false;int chunks=0,at=8;
        while(at<source.length) {
            check();if(++chunks>4096)throw fail(ModelPlan.Code.LIMIT,"","PNG chunk-count budget reached");
            require(source.length-at>=12,"","Truncated PNG chunk");long size=u32(at);require(size<=Integer.MAX_VALUE && size<=source.length-at-12L,"","Invalid PNG chunk length");
            int bytes=(int)size,start=at+8,next=start+bytes+4;
            for(int i=4;i<8;i++)require(letter(source[at+i]&255),"","Invalid PNG chunk name");
            require((source[at+6]&32)==0,"","Reserved PNG chunk-name bit is set");
            String name=new String(source,at+4,4,java.nio.charset.StandardCharsets.US_ASCII);
            CRC32 crc=new CRC32();crc.update(source,at+4,4);
            for(int done=0;done<bytes;) { check();int take=Math.min(65536,bytes-done);crc.update(source,start+done,take);done+=take; }
            require(crc.getValue()==u32(start+bytes),"/"+name,"PNG chunk CRC mismatch");
            require(header||name.equals("IHDR"),"","PNG IHDR must be first");
            if(seenData&&!name.equals("IDAT"))closedData=true;
            switch(name) {
                case "IHDR" -> {
                    require(!header && bytes==13,"/IHDR","Duplicate or malformed PNG header");header=true;
                    long w=u32(start),h=u32(start+4);
                    if(w<1||h<1||w>limits.imageDimension()||h>limits.imageDimension()||w*h>limits.imagePixels()||w*h*4>remainingRgba)
                        throw fail(ModelPlan.Code.LIMIT,"/IHDR","PNG dimensions or remaining RGBA byte budget exceeded");
                    width=(int)w;height=(int)h;depth=source[start+8]&255;type=source[start+9]&255;
                    channels=switch(type){case 0,3->1;case 2->3;case 4->2;case 6->4;default->0;};
                    boolean valid=switch(type){case 0->depth==1||depth==2||depth==4||depth==8||depth==16;case 3->depth==1||depth==2||depth==4||depth==8;case 2,4,6->depth==8||depth==16;default->false;};
                    require(valid,"/IHDR","Unsupported PNG color type or sample depth");
                    interlace=source[start+12]&255;require(source[start+10]==0&&source[start+11]==0&&interlace<=1,"/IHDR","Unsupported PNG compression, filter or interlace method");
                }
                case "PLTE" -> {
                    require(!seenData&&palette==null&&transparency==null&&type!=0&&type!=4&&bytes>=3&&bytes<=768&&bytes%3==0,"/PLTE","Invalid PNG palette or ordering");
                    require(type!=3||bytes/3<=(1<<depth),"/PLTE","PNG palette exceeds index depth");palette=Arrays.copyOfRange(source,start,start+bytes);
                }
                case "tRNS" -> {
                    require(!seenData&&transparency==null,"/tRNS","Duplicate or late PNG transparency");
                    require(switch(type){case 0->bytes==2;case 2->bytes==6;case 3->palette!=null&&bytes>=1&&bytes<=palette.length/3;default->false;},"/tRNS","Invalid PNG transparency for color type");
                    transparency=Arrays.copyOfRange(source,start,start+bytes);
                    if(type==0||type==2)for(int i=0;i<bytes;i+=2)require(((transparency[i]&255)<<8|(transparency[i+1]&255))<=(depth==16?65535:(1<<depth)-1),"/tRNS","Transparency key exceeds sample depth");
                }
                case "IDAT" -> {
                    require(!closedData && (type!=3||palette!=null),"/IDAT","Nonconsecutive PNG data or missing palette");seenData=true;data.add(new Segment(start,bytes));
                }
                case "IEND" -> {
                    require(seenData&&bytes==0&&next==source.length,"/IEND","Invalid PNG end or trailing file bytes");end=true;
                }
                default -> require((source[at+4]&32)!=0,"/"+name,"Unknown critical PNG chunk");
            }
            at=next;if(end)break;
        }
        require(header&&seenData&&end,"","Incomplete PNG datastream");check();
    }
    private int rowBytes(int w) { return (int)(((long)w*channels*depth+7)/8); }
    private static int span(int size,int start,int step) { return size<=start?0:(size-start+step-1)/step; }
    private RgbaImage image() {
        long raw=0;int passes=interlace==0?1:7;
        for(int p=0;p<passes;p++) { int w=interlace==0?width:span(width,X[p],DX[p]),h=interlace==0?height:span(height,Y[p],DY[p]);if(w>0&&h>0)raw+=(long)(rowBytes(w)+1)*h; }
        if(raw>256L*1024*1024)throw fail(ModelPlan.Code.LIMIT,"/IDAT","Inflated PNG scanline budget exceeded");
        byte[] rgba=new byte[width*height*4];
        try(var stream=new Pixels()) {
            for(int p=0;p<passes;p++) {
                check();int x0=interlace==0?0:X[p],y0=interlace==0?0:Y[p],dx=interlace==0?1:DX[p],dy=interlace==0?1:DY[p];
                int w=span(width,x0,dx),h=span(height,y0,dy);if(w==0||h==0)continue;
                int length=rowBytes(w),bpp=Math.max(1,(channels*depth+7)/8);byte[] previous=new byte[length],row=new byte[length],filter=new byte[1];
                for(int y=0;y<h;y++) {
                    check();stream.exact(filter);stream.exact(row);int method=filter[0]&255;require(method<=4,"/IDAT","Invalid PNG scanline filter");
                    for(int x=0;x<length;x++) {
                        if((x&4095)==0)check();int a=x>=bpp?row[x-bpp]&255:0,b=previous[x]&255,c=x>=bpp?previous[x-bpp]&255:0;
                        int predictor=switch(method){case 0->0;case 1->a;case 2->b;case 3->(a+b)/2;case 4->paeth(a,b,c);default->throw new AssertionError();};row[x]=(byte)((row[x]&255)+predictor);
                    }
                    for(int x=0;x<w;x++) { if((x&1023)==0)check();pixel(row,x,rgba,((y0+y*dy)*width+x0+x*dx)*4); }
                    byte[] swap=previous;previous=row;row=swap;
                }
            }
            stream.finish();check();return RgbaImage.owned(width,height,rgba);
        }
    }
    private static int paeth(int a,int b,int c) { int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c; }
    private int sample(byte[] row,int number) {
        if(depth==16)return (row[number*2]&255)<<8|row[number*2+1]&255;
        if(depth==8)return row[number]&255;int bit=number*depth;return (row[bit/8]>>>(8-depth-bit%8))&((1<<depth)-1);
    }
    private int eight(int sample) { return depth==16?sample>>>8:depth==8?sample:sample*255/((1<<depth)-1); }
    private int key(int index) { return (transparency[index*2]&255)<<8|transparency[index*2+1]&255; }
    private void pixel(byte[] row,int x,byte[] rgba,int at) {
        int base=x*channels,s0=sample(row,base),r,g,b,a=255;
        switch(type) {
            case 0 -> { r=g=b=eight(s0);if(transparency!=null&&s0==key(0))a=0; }
            case 2 -> { int s1=sample(row,base+1),s2=sample(row,base+2);r=eight(s0);g=eight(s1);b=eight(s2);if(transparency!=null&&s0==key(0)&&s1==key(1)&&s2==key(2))a=0; }
            case 3 -> { require(s0<palette.length/3,"/IDAT","PNG pixel references an absent palette entry");r=palette[s0*3]&255;g=palette[s0*3+1]&255;b=palette[s0*3+2]&255;if(transparency!=null&&s0<transparency.length)a=transparency[s0]&255; }
            case 4 -> { r=g=b=eight(s0);a=eight(sample(row,base+1)); }
            case 6 -> { r=eight(s0);g=eight(sample(row,base+1));b=eight(sample(row,base+2));a=eight(sample(row,base+3)); }
            default -> throw new AssertionError();
        }
        rgba[at]=(byte)r;rgba[at+1]=(byte)g;rgba[at+2]=(byte)b;rgba[at+3]=(byte)a;
    }
    private final class Pixels implements AutoCloseable {
        final Inflater inflater=new Inflater();int segment;
        int read(byte[] output,int offset,int length) {
            while(true) {
                check();if(inflater.finished())return -1;
                if(inflater.needsDictionary())throw fail(ModelPlan.Code.TEXTURE_FORMAT,"/IDAT","PNG zlib dictionary is not permitted");
                if(inflater.needsInput()) {
                    while(segment<data.size() && data.get(segment).bytes()==0)segment++;
                    require(segment<data.size(),"/IDAT","Truncated PNG zlib stream");var input=data.get(segment++);inflater.setInput(source,input.offset(),input.bytes());
                }
                int count;try { count=inflater.inflate(output,offset,Math.min(length,65536)); }
                catch(DataFormatException invalid) { throw fail(ModelPlan.Code.TEXTURE_FORMAT,"/IDAT","Invalid PNG zlib stream"); }
                if(count>0)return count;
                require(inflater.finished()||inflater.needsInput()||inflater.needsDictionary(),"/IDAT","PNG zlib stream made no progress");
            }
        }
        void exact(byte[] output) { int done=0;while(done<output.length){int count=read(output,done,output.length-done);require(count>0,"/IDAT","PNG contains too few scanline bytes");done+=count;} }
        void finish() {
            require(read(new byte[1],0,1)==-1,"/IDAT","PNG contains excess scanline bytes");require(inflater.getRemaining()==0,"/IDAT","Trailing bytes after PNG zlib stream");
            while(segment<data.size())require(data.get(segment++).bytes()==0,"/IDAT","Extra PNG compressed data");
        }
        @Override public void close() { inflater.end(); }
    }
}
