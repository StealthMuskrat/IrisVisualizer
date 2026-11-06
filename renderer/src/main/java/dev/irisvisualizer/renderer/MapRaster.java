package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Atomic map colorization with original native samples retained for inspection. */
public final class MapRaster {
    public sealed interface Style permits ScalarStyle,BiomeStyle,TerrainStyle {}
    public record ScalarStyle(double minimum,double maximum,MapRamp ramp) implements Style {
        public ScalarStyle { Objects.requireNonNull(ramp);if(!Double.isFinite(minimum)||!Double.isFinite(maximum)||minimum>maximum)throw new IllegalArgumentException("Invalid display range"); }
        double normalize(double value) {
            if(minimum==maximum)return 0.5;if(value<=minimum)return 0;if(value>=maximum)return 1;double span=maximum-minimum;
            return Double.isFinite(span)?(value-minimum)/span:(value/2-minimum/2)/(maximum/2-minimum/2);
        }
        /** Display-legend interpolation, including ranges whose subtraction overflows. */
        public double valueAt(double position) {
            if(!Double.isFinite(position)||position<0||position>1)throw new IllegalArgumentException("Legend position must be in [0,1]");
            if(position==0)return minimum;if(position==1)return maximum;if(minimum==maximum)return minimum;double span=maximum-minimum;
            double value=Double.isFinite(span)?minimum+span*position:minimum*(1-position)+maximum*position;
            return Math.clamp(value,minimum,maximum);
        }
    }
    /** Colors correspond exactly to the source biome palette, never to a replacement registry. */
    public record BiomeStyle(List<Integer> rgba) implements Style { public BiomeStyle { Objects.requireNonNull(rgba);if(rgba.isEmpty()||rgba.size()>8192)throw new IllegalArgumentException("Invalid biome color palette");rgba=List.copyOf(rgba); } }
    /** Direction points toward a display light in world X/Y/Z; native height samples stay unchanged. */
    public record Relief(double lightX,double lightY,double lightZ,double ambient,double amount) {
        public static final Relief DEFAULT=new Relief(-1,2,-1,0.35,0.7);
        public Relief {
            if(!Double.isFinite(lightX)||!Double.isFinite(lightY)||!Double.isFinite(lightZ)||
                    Math.max(Math.abs(lightX),Math.max(Math.abs(lightY),Math.abs(lightZ)))==0||
                    !Double.isFinite(ambient)||ambient<0||ambient>1||!Double.isFinite(amount)||amount<0||amount>1)
                throw new IllegalArgumentException("Invalid terrain display lighting");
        }
    }
    public record TerrainStyle(ScalarStyle colors,Relief relief) implements Style {
        public TerrainStyle { Objects.requireNonNull(colors);Objects.requireNonNull(relief); }
    }
    public record Limits(long imageBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(16L*1024*1024,64L*1024*1024);
        public Limits { if(imageBytes<0||imageBytes>16L*1024*1024||workingBytes<0||workingBytes>64L*1024*1024)throw new IllegalArgumentException("Map raster limits exceed renderer bounds"); }
    }
    public record Summary(int present,int absent,OptionalDouble minimum,OptionalDouble maximum,int reliefSamples) {
        public Summary(int present,int absent,OptionalDouble minimum,OptionalDouble maximum) { this(present,absent,minimum,maximum,0); }
        public Summary { Objects.requireNonNull(minimum);Objects.requireNonNull(maximum);if(present<0||absent<0||minimum.isPresent()!=maximum.isPresent()||reliefSamples<0||reliefSamples>present)throw new IllegalArgumentException("Invalid map summary"); }
    }
    private final MapSamples samples;private final Style style;private final Optional<RgbaImage> image;private final Optional<Summary> summary;private final List<WorldProblem> problems;
    private MapRaster(MapSamples samples,Style style,Optional<RgbaImage> image,Optional<Summary> summary,List<WorldProblem> problems) { this.samples=samples;this.style=style;this.image=image;this.summary=summary;this.problems=List.copyOf(problems);if(!problems.isEmpty()&&(image.isPresent()||summary.isPresent()))throw new IllegalArgumentException("Partial map raster escaped failure"); }
    public static ScalarStyle terrainStyle(MapSamples samples) { if(samples.kind()!=MapSamples.Kind.TERRAIN||samples.requiresPlaceholder())throw new IllegalArgumentException("Complete terrain samples required");var range=samples.vertical().orElseThrow();return new ScalarStyle(range.minY(),range.maxY()-1,MapRamp.GRAYSCALE); }
    /** Explicit opt-in; the existing terrainStyle helper remains a flat full-height gradient. */
    public static TerrainStyle terrainReliefStyle(MapSamples samples) { return new TerrainStyle(terrainStyle(samples),Relief.DEFAULT); }
    /** Explicit worker-side histogram of the complete original scalar map at this display range. */
    public ScalarHistogram histogram(ScalarHistogram.Limits limits,BooleanSupplier cancelled) {
        if(requiresPlaceholder())throw new IllegalStateException("Complete map raster required for a display histogram");
        if(style instanceof BiomeStyle)throw new IllegalStateException("Biome maps are categorical, not scalar histograms");
        var colors=style instanceof TerrainStyle terrain?terrain.colors():(ScalarStyle)style;
        return ScalarHistogram.capture(samples,Optional.of(new ScalarHistogram.Range(colors.minimum(),colors.maximum())),Objects.requireNonNull(limits).reserve(payloadBytes()-samples.payloadBytes()),cancelled);
    }
    /** Complete old raster plus prospective replacement RGBA; equal value styles reuse the original image. */
    public static long recolorReservation(MapRaster source,Style style) {
        Objects.requireNonNull(source);Objects.requireNonNull(style);if(source.requiresPlaceholder())throw new IllegalArgumentException("Complete map raster required for recoloring");
        return source.payloadBytes()+(source.style.equals(style)?0:source.samples.grid().count()*4);
    }
    /** Reuses exact native samples. No native callback or generator is consulted for display-style changes. */
    public static MapRaster recolor(MapRaster source,Style style,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(source);Objects.requireNonNull(style);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(source.requiresPlaceholder())return new MapRaster(source.samples,style,Optional.empty(),Optional.empty(),source.problems);
        try {
            if(source.image.orElseThrow().bytes()>limits.imageBytes()||recolorReservation(source,style)>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Complete map source and replacement colors exceed their working allowance");
            if(source.style.equals(style)) { WorldProblem.check(cancelled);return source; }
            return execute(source.samples,style,limits,cancelled);
        }catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new MapRaster(source.samples,style,Optional.empty(),Optional.empty(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return new MapRaster(source.samples,style,Optional.empty(),Optional.empty(),List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to recolor map samples; no partial image was published"))); }
    }
    public static MapRaster render(MapSamples samples,Style style,BooleanSupplier cancelled) { return render(samples,style,Limits.DEFAULT,cancelled); }
    public static MapRaster render(MapSamples samples,Style style,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(samples);Objects.requireNonNull(style);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(samples,style,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new MapRaster(samples,style,Optional.empty(),Optional.empty(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return new MapRaster(samples,style,Optional.empty(),Optional.empty(),List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to colorize map samples; no partial image was published"))); }
    }
    private static MapRaster execute(MapSamples samples,Style style,Limits limits,BooleanSupplier cancelled) {
        if(samples.requiresPlaceholder())return new MapRaster(samples,style,Optional.empty(),Optional.empty(),samples.problems());
        if(samples.kind()==MapSamples.Kind.BIOME) { if(!(style instanceof BiomeStyle biome)||biome.rgba().size()!=samples.biomePalette().size())throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Provide one display color for every exact native map biome"); }
        else if(!(style instanceof ScalarStyle)&&!(style instanceof TerrainStyle&&samples.kind()==MapSamples.Kind.TERRAIN))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Terrain/noise require scalar colors; relief requires native terrain heights");
        long bytes=samples.grid().count()*4;if(bytes>limits.imageBytes()||samples.payloadBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Map raster or combined primitive payload budget exceeded");
        byte[] output=new byte[(int)bytes];int present=0;double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY;
        var shading=style instanceof TerrainStyle terrain?new Shading(terrain.relief()):null;
        for(int at=0;at<samples.size();at++) {
            if((at&4095)==0)WorldProblem.check(cancelled);if(!samples.present(at))continue;present++;int rgba;
            if(style instanceof BiomeStyle biome)rgba=biome.rgba().get(samples.biomeIndex(at));
            else {
                double value=samples.kind()==MapSamples.Kind.TERRAIN?samples.height(at):samples.noise(at);minimum=Math.min(minimum,value);maximum=Math.max(maximum,value);
                var scalar=style instanceof TerrainStyle terrain?terrain.colors():(ScalarStyle)style;rgba=scalar.ramp().rgba(scalar.normalize(value));
                if(shading!=null)rgba=shading.rgba(samples,at,rgba);
            }
            int offset=at*4;output[offset]=(byte)(rgba>>>24);output[offset+1]=(byte)(rgba>>>16);output[offset+2]=(byte)(rgba>>>8);output[offset+3]=(byte)rgba;
        }
        WorldProblem.check(cancelled);var summary=new Summary(present,samples.size()-present,Double.isFinite(minimum)?OptionalDouble.of(minimum):OptionalDouble.empty(),Double.isFinite(maximum)?OptionalDouble.of(maximum):OptionalDouble.empty(),shading==null?0:shading.samples);
        return new MapRaster(samples,style,Optional.of(RgbaImage.owned(samples.grid().width(),samples.grid().height(),output)),Optional.of(summary),List.of());
    }
    /** One job-local light, no per-pixel arrays, no halo sampling or substituted neighbor heights. */
    private static final class Shading {
        final double lightX,lightY,lightZ,ambient,amount;int samples;
        Shading(Relief relief) {
            double scale=Math.max(Math.abs(relief.lightX()),Math.max(Math.abs(relief.lightY()),Math.abs(relief.lightZ())));
            double x=relief.lightX()/scale,y=relief.lightY()/scale,z=relief.lightZ()/scale,length=Math.hypot(Math.hypot(x,y),z);
            lightX=x/length;lightY=y/length;lightZ=z/length;ambient=relief.ambient();amount=relief.amount();
        }
        int rgba(MapSamples source,int at,int base) {
            if(amount==0)return base;var grid=source.grid();int width=grid.width(),x=at%width,z=at/width;
            if(x==0||x==width-1||z==0||z==grid.height()-1||
                    !source.present(at-1)||!source.present(at+1)||!source.present(at-width)||!source.present(at+width))return base;
            double dx=(double)source.height(at+1)-source.height(at-1),dz=(double)source.height(at+width)-source.height(at-width),step=grid.blocksPerSample();
            // Equivalent to (-dx/(2s), 1, -dz/(2s)), scaled before division to avoid overflow/subnormal slopes.
            double scale=Math.max(step,Math.max(Math.abs(dx),Math.abs(dz))),nx=-dx/scale,ny=2*(step/scale),nz=-dz/scale,length=Math.hypot(Math.hypot(nx,ny),nz);
            double diffuse=Math.clamp((nx*lightX+ny*lightY+nz*lightZ)/length,0,1);
            double factor=1-amount*(1-(ambient+(1-ambient)*diffuse));samples++;int result=base&255;
            for(int shift=24;shift>=8;shift-=8)result|=(int)Math.round((base>>>shift&255)*factor)<<shift;
            return result;
        }
    }
    public MapSamples samples() { return samples; }
    public Style style() { return style; }
    public Optional<RgbaImage> image() { return image; }
    public Optional<Summary> summary() { return summary; }
    public long payloadBytes() { return samples.payloadBytes()+image.map(value->(long)value.bytes()).orElse(0L); }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
