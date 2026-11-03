package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Atomic map colorization with original native samples retained for inspection. */
public final class MapRaster {
    public sealed interface Style permits ScalarStyle,BiomeStyle {}
    public record ScalarStyle(double minimum,double maximum,MapRamp ramp) implements Style {
        public ScalarStyle { Objects.requireNonNull(ramp);if(!Double.isFinite(minimum)||!Double.isFinite(maximum)||minimum>maximum)throw new IllegalArgumentException("Invalid display range"); }
        double normalize(double value) {
            if(minimum==maximum)return 0.5;if(value<=minimum)return 0;if(value>=maximum)return 1;double span=maximum-minimum;
            return Double.isFinite(span)?(value-minimum)/span:(value/2-minimum/2)/(maximum/2-minimum/2);
        }
    }
    /** Colors correspond exactly to the source biome palette, never to a replacement registry. */
    public record BiomeStyle(List<Integer> rgba) implements Style { public BiomeStyle { Objects.requireNonNull(rgba);if(rgba.isEmpty()||rgba.size()>8192)throw new IllegalArgumentException("Invalid biome color palette");rgba=List.copyOf(rgba); } }
    public record Limits(long imageBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(16L*1024*1024,64L*1024*1024);
        public Limits { if(imageBytes<0||imageBytes>16L*1024*1024||workingBytes<0||workingBytes>64L*1024*1024)throw new IllegalArgumentException("Map raster limits exceed renderer bounds"); }
    }
    public record Summary(int present,int absent,OptionalDouble minimum,OptionalDouble maximum) {
        public Summary { Objects.requireNonNull(minimum);Objects.requireNonNull(maximum);if(present<0||absent<0||minimum.isPresent()!=maximum.isPresent())throw new IllegalArgumentException("Invalid map summary"); }
    }
    private final MapSamples samples;private final Style style;private final Optional<RgbaImage> image;private final Optional<Summary> summary;private final List<WorldProblem> problems;
    private MapRaster(MapSamples samples,Style style,Optional<RgbaImage> image,Optional<Summary> summary,List<WorldProblem> problems) { this.samples=samples;this.style=style;this.image=image;this.summary=summary;this.problems=List.copyOf(problems);if(!problems.isEmpty()&&(image.isPresent()||summary.isPresent()))throw new IllegalArgumentException("Partial map raster escaped failure"); }
    public static ScalarStyle terrainStyle(MapSamples samples) { if(samples.kind()!=MapSamples.Kind.TERRAIN||samples.requiresPlaceholder())throw new IllegalArgumentException("Complete terrain samples required");var range=samples.vertical().orElseThrow();return new ScalarStyle(range.minY(),range.maxY()-1,MapRamp.GRAYSCALE); }
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
        else if(!(style instanceof ScalarStyle))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Terrain and noise maps require an explicit scalar display style");
        long bytes=samples.grid().count()*4;if(bytes>limits.imageBytes()||samples.payloadBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Map raster or combined primitive payload budget exceeded");
        byte[] output=new byte[(int)bytes];int present=0;double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY;
        for(int at=0;at<samples.size();at++) {
            if((at&4095)==0)WorldProblem.check(cancelled);if(!samples.present(at))continue;present++;int rgba;
            if(style instanceof BiomeStyle biome)rgba=biome.rgba().get(samples.biomeIndex(at));
            else { double value=samples.kind()==MapSamples.Kind.TERRAIN?samples.height(at):samples.noise(at);minimum=Math.min(minimum,value);maximum=Math.max(maximum,value);var scalar=(ScalarStyle)style;rgba=scalar.ramp().rgba(scalar.normalize(value)); }
            int offset=at*4;output[offset]=(byte)(rgba>>>24);output[offset+1]=(byte)(rgba>>>16);output[offset+2]=(byte)(rgba>>>8);output[offset+3]=(byte)rgba;
        }
        WorldProblem.check(cancelled);var summary=new Summary(present,samples.size()-present,Double.isFinite(minimum)?OptionalDouble.of(minimum):OptionalDouble.empty(),Double.isFinite(maximum)?OptionalDouble.of(maximum):OptionalDouble.empty());
        return new MapRaster(samples,style,Optional.of(RgbaImage.owned(samples.grid().width(),samples.grid().height(),output)),Optional.of(summary),List.of());
    }
    public MapSamples samples() { return samples; }
    public Style style() { return style; }
    public Optional<RgbaImage> image() { return image; }
    public Optional<Summary> summary() { return summary; }
    public long payloadBytes() { return samples.payloadBytes()+image.map(value->(long)value.bytes()).orElse(0L); }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
