package dev.irisvisualizer.editor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Exact decoded RGBA8 statistics only; no encoded-source edits, sampling simulation or native interpretation. */
public final class ImageStatistics {
    public record Region(int x,int y,int width,int height) {
        public Region { if(x<0 || y<0 || width<1 || height<1)throw new IllegalArgumentException("Use nonnegative X/Y and positive region width/height"); }
        public void validate(int imageWidth,int imageHeight) {
            try { AssetImage.checkSize(imageWidth,imageHeight); }catch(java.io.IOException invalid) { throw new IllegalArgumentException(invalid.getMessage(),invalid); }
            if((long)x+width>imageWidth || (long)y+height>imageHeight)throw new IllegalArgumentException("Region must be inside the decoded image");
        }
        public long pixels() { return (long)width*height; }
        public static Region whole(AssetImage.Decoded image) { Objects.requireNonNull(image);return new Region(0,0,image.width(),image.height()); }
        public static Region between(ImageViewport.Pixel first,ImageViewport.Pixel last,int width,int height) {
            Objects.requireNonNull(first);Objects.requireNonNull(last);
            new Region(first.x(),first.y(),1,1).validate(width,height);new Region(last.x(),last.y(),1,1).validate(width,height);
            return new Region(Math.min(first.x(),last.x()),Math.min(first.y(),last.y()),Math.abs(first.x()-last.x())+1,Math.abs(first.y()-last.y())+1);
        }
    }
    public record Channel(int minimum,int maximum,long sum,long samples,long[] bins) {
        public Channel {
            Objects.requireNonNull(bins);if(minimum<0 || maximum>255 || minimum>maximum || samples<1 || samples>AssetImage.MAX_PIXELS || sum<0 || bins.length!=256)throw new IllegalArgumentException("Invalid image channel statistics");bins=bins.clone();
            long total=0,weighted=0;int low=-1,high=-1;for(int i=0;i<256;i++){if(bins[i]<0 || bins[i]>samples)throw new IllegalArgumentException("Invalid histogram count");total+=bins[i];weighted+=i*bins[i];if(bins[i]>0){if(low<0)low=i;high=i;}}
            if(total!=samples || weighted!=sum || low!=minimum || high!=maximum)throw new IllegalArgumentException("Histogram and channel totals disagree");
        }
        @Override public long[] bins() { return bins.clone(); }
        public double mean() { return sum/(double)samples; }
        public long count(int value) { if(value<0 || value>255)throw new IllegalArgumentException("Use a channel value from 0 to 255");return bins[value]; }
        public float[] plot() { var values=new float[256];for(int i=0;i<256;i++)values[i]=bins[i];return values; }
    }
    public record Result(Region region,List<Channel> channels,long transparent,long partial,long opaque) {
        public Result { Objects.requireNonNull(region);channels=List.copyOf(channels);if(channels.size()!=4 || region.pixels()>AssetImage.MAX_PIXELS || transparent<0 || partial<0 || opaque<0 || transparent>region.pixels() || partial>region.pixels() || opaque>region.pixels() || transparent+partial+opaque!=region.pixels() || channels.stream().anyMatch(channel->channel.samples()!=region.pixels()))throw new IllegalArgumentException("Invalid image statistics result"); }
    }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Image statistics cancelled"); }
    public static Result analyze(AssetImage.Decoded image,Region region,BooleanSupplier cancelled,LongConsumer progress) {
        Objects.requireNonNull(image);Objects.requireNonNull(region);Objects.requireNonNull(cancelled);Objects.requireNonNull(progress);region.validate(image.width(),image.height());check(cancelled);
        var bins=new long[4][256];var sums=new long[4];long transparent=0,partial=0,opaque=0,processed=0;
        for(int y=region.y();y<region.y()+region.height();y++) {
            check(cancelled);
            for(int x=region.x();x<region.x()+region.width();x++) {
                if((x&255)==0)check(cancelled);int pixel=image.argbAt(x,y),alpha=pixel>>>24;
                int red=(pixel>>>16)&255,green=(pixel>>>8)&255,blue=pixel&255;
                bins[0][red]++;sums[0]+=red;bins[1][green]++;sums[1]+=green;bins[2][blue]++;sums[2]+=blue;bins[3][alpha]++;sums[3]+=alpha;
                if(alpha==0)transparent++;else if(alpha==255)opaque++;else partial++;
            }
            processed+=region.width();progress.accept(processed);
        }
        check(cancelled);var channels=new java.util.ArrayList<Channel>(4);
        for(int channel=0;channel<4;channel++) { int low=0,high=255;while(bins[channel][low]==0)low++;while(bins[channel][high]==0)high--;channels.add(new Channel(low,high,sums[channel],region.pixels(),bins[channel])); }
        return new Result(region,channels,transparent,partial,opaque);
    }
    private ImageStatistics() {}
}
