package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached full-source scalar distribution. No resampling, source normalization or missing-value substitution. */
public final class ScalarHistogram {
    public enum Kind { TERRAIN_MAP, NOISE_MAP, NOISE_VOLUME }
    public record Range(double minimum,double maximum) {
        public Range { if(!Double.isFinite(minimum)||!Double.isFinite(maximum)||minimum>maximum)throw new IllegalArgumentException("Invalid histogram display range"); }
        public MapRaster.ScalarStyle style(MapRamp ramp) { return new MapRaster.ScalarStyle(minimum,maximum,ramp); }
    }
    public record Limits(int bins,long samples,long visits,long payloadBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(256,16777216,33554432,65536,512L*1024*1024);
        public Limits {
            if(bins<1||bins>4096||samples<1||samples>16777216||visits<0||visits>33554432||payloadBytes<0||payloadBytes>65536||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Scalar histogram limits exceed renderer bounds");
        }
        Limits reserve(long borrowedBytes) {
            if(borrowedBytes<0)throw new IllegalArgumentException("Negative borrowed histogram payload");
            return new Limits(bins,samples,visits,payloadBytes,Math.max(0,workingBytes-borrowedBytes));
        }
    }
    public record Receipt(Kind kind,UUID sourceId,MapSamples.Context context,MapSamples.Grid horizontal,Optional<MapSamples.Vertical> vertical) {
        public Receipt { Objects.requireNonNull(kind);Objects.requireNonNull(sourceId);Objects.requireNonNull(context);Objects.requireNonNull(horizontal);Objects.requireNonNull(vertical); }
    }
    public record Summary(int present,int absent,int inRange,int below,int above,OptionalDouble minimum,OptionalDouble maximum,long visits) {
        public Summary {
            Objects.requireNonNull(minimum);Objects.requireNonNull(maximum);
            if(present<0||absent<0||inRange<0||below<0||above<0||(long)inRange+below+above!=present||minimum.isPresent()!=maximum.isPresent()||minimum.isPresent()!=(present>0)||visits<0)throw new IllegalArgumentException("Invalid scalar histogram coverage");
        }
    }
    private interface Reader { boolean present(int at);double value(int at); }
    private record Input(Receipt receipt,int size,long bytes,List<WorldProblem> problems,Reader reader) {}
    private static final long BASE_BYTES=256;
    private final Receipt receipt;private final Optional<Range> range;private final int[] counts;private final Optional<Summary> summary;private final List<WorldProblem> problems;private final long bytes;
    private ScalarHistogram(Receipt receipt,Optional<Range> range,int[] counts,Optional<Summary> summary,List<WorldProblem> problems,long bytes) {
        this.receipt=receipt;this.range=range;this.counts=counts;this.summary=summary;this.problems=List.copyOf(problems);this.bytes=bytes;
        if(problems.isEmpty()!=summary.isPresent()||!problems.isEmpty()&&(counts.length!=0||range.isPresent()||bytes!=0))throw new IllegalArgumentException("Partial scalar histogram escaped failure");
    }
    private static Input input(MapSamples source) {
        Objects.requireNonNull(source);if(source.kind()==MapSamples.Kind.BIOME)throw new IllegalArgumentException("Biome palettes are categorical, not scalar distributions");
        var kind=source.kind()==MapSamples.Kind.TERRAIN?Kind.TERRAIN_MAP:Kind.NOISE_MAP;
        return new Input(new Receipt(kind,source.id(),source.context(),source.grid(),source.vertical()),source.size(),source.payloadBytes(),source.problems(),new Reader() {
            public boolean present(int at) { return source.present(at); }
            public double value(int at) { return source.kind()==MapSamples.Kind.TERRAIN?source.height(at):source.noise(at); }
        });
    }
    private static Input input(NoiseVolume source) {
        Objects.requireNonNull(source);return new Input(new Receipt(Kind.NOISE_VOLUME,source.id(),source.context(),source.grid().horizontal(),Optional.of(source.grid().vertical())),source.size(),source.payloadBytes(),source.problems(),new Reader() {
            public boolean present(int at) { return source.present(at); }
            public double value(int at) { return source.value(at); }
        });
    }
    public Receipt receipt() { return receipt; }
    public Optional<Range> range() { return range; }
    public Optional<Summary> summary() { return summary; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public int bins() { return counts.length; }
    public int count(int bin) { return counts[Objects.checkIndex(bin,counts.length)]; }
    /** Independent caller-owned copy; copying is outside this result's retained admission. */
    public int[] copyCounts() { return counts.clone(); }
    public long bytes() { return bytes; }
    public boolean matches(MapSamples source) { return !requiresPlaceholder()&&!source.requiresPlaceholder()&&source.kind()!=MapSamples.Kind.BIOME&&receipt.equals(input(source).receipt()); }
    public boolean matches(NoiseVolume source) { return !requiresPlaceholder()&&!source.requiresPlaceholder()&&receipt.equals(input(source).receipt()); }
    /** Match both source identity and display range; changing only the gradient does not change bins. */
    public boolean matches(MapRaster raster) {
        Objects.requireNonNull(raster);if(raster.requiresPlaceholder()||raster.style() instanceof MapRaster.BiomeStyle)return false;
        var colors=raster.style() instanceof MapRaster.TerrainStyle terrain?terrain.colors():(MapRaster.ScalarStyle)raster.style();
        return matches(raster.samples())&&range.filter(value->value.equals(new Range(colors.minimum(),colors.maximum()))).isPresent();
    }
    /** Full-volume distributions can survive inclusion/meshing changes when source and range stay exact. */
    public boolean matches(NoiseSurface surface) {
        Objects.requireNonNull(surface);if(surface.requiresPlaceholder())return false;var colors=surface.style().colors();
        return matches(surface.volume())&&range.filter(value->value.equals(new Range(colors.minimum(),colors.maximum()))).isPresent();
    }
    public Optional<MapRaster.ScalarStyle> displayStyle(MapRamp ramp) { Objects.requireNonNull(ramp);return range.map(value->value.style(ramp)); }
    /** Bin edges are display approximations; counts use the exact existing colorizer normalization. */
    public OptionalDouble boundary(int edge) {
        if(requiresPlaceholder())throw new IllegalStateException("Histogram capture failed");if(edge<0||edge>counts.length)throw new IndexOutOfBoundsException("Histogram edge outside bin count");
        return range.isEmpty()?OptionalDouble.empty():OptionalDouble.of(range.orElseThrow().style(MapRamp.GRAYSCALE).valueAt((double)edge/counts.length));
    }
    private static long payload(Receipt receipt,Limits limits) {
        var context=receipt.context();var source=context.world();long characters=(long)source.inputSha256().length()+source.backendBuild().length()+source.minecraftVersion().length()+source.dimension().namespace().length()+source.dimension().path().length()+context.resourceType().length()+context.resourceKey().length()+context.samplingPolicy().length();
        return BASE_BYTES+(long)limits.bins()*Integer.BYTES+characters*Character.BYTES;
    }
    public static long workingBytes(MapSamples source,Limits limits) { Objects.requireNonNull(limits);var input=input(source);return input.bytes()+payload(input.receipt(),limits); }
    public static long workingBytes(NoiseVolume source,Limits limits) { Objects.requireNonNull(limits);var input=input(source);return input.bytes()+payload(input.receipt(),limits); }
    public static ScalarHistogram capture(MapSamples source,BooleanSupplier cancelled) { return capture(source,Optional.empty(),Limits.DEFAULT,cancelled); }
    public static ScalarHistogram capture(NoiseVolume source,BooleanSupplier cancelled) { return capture(source,Optional.empty(),Limits.DEFAULT,cancelled); }
    /** Empty requested range selects the observed range; an all-absent source leaves that range empty. */
    public static ScalarHistogram capture(MapSamples source,Optional<Range> range,Limits limits,BooleanSupplier cancelled) { return capture(input(source),range,limits,cancelled); }
    public static ScalarHistogram capture(NoiseVolume source,Optional<Range> range,Limits limits,BooleanSupplier cancelled) { return capture(input(source),range,limits,cancelled); }
    private static ScalarHistogram capture(Input input,Optional<Range> range,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(range);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(input,range,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(input.receipt(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(input.receipt(),List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for a scalar histogram; no partial counts were published"))); }
    }
    public static RenderInput<ScalarHistogram> captureOwned(MapSamples source,Optional<Range> range,Limits limits,RenderMemory memory,BooleanSupplier cancelled) { return captureOwned(input(source),range,limits,memory,cancelled); }
    public static RenderInput<ScalarHistogram> captureOwned(NoiseVolume source,Optional<Range> range,Limits limits,RenderMemory memory,BooleanSupplier cancelled) { return captureOwned(input(source),range,limits,memory,cancelled); }
    private static RenderInput<ScalarHistogram> captureOwned(Input input,Optional<Range> range,Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(range);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(!input.problems().isEmpty())throw new WorldCapture.ReadFailure(input.problems().getFirst());
        long peak=input.bytes()+payload(input.receipt(),limits);if(peak>limits.workingBytes())throw new WorldCapture.ReadFailure(new WorldProblem(WorldProblem.Code.LIMIT,Optional.empty(),"Owned scalar histogram exceeds its working allowance"));
        var hold=memory==null?RenderMemory.Hold.none():memory.hold("Scalar histogram",peak);
        Throwable escaping=null;
        try {
            var result=capture(input,range,limits,cancelled);if(result.requiresPlaceholder())throw new WorldCapture.ReadFailure(result.problems().getFirst());WorldProblem.check(cancelled);
            hold.shrinkTo(result.bytes());return RenderInput.own(result,hold);
        }catch(RuntimeException|Error failure) { escaping=failure;throw failure; }
        finally {
            try { hold.close(); }
            catch(RuntimeException|Error cleanup) { if(escaping==null)throw cleanup;if(cleanup!=escaping)escaping.addSuppressed(cleanup); }
        }
    }
    private static ScalarHistogram failed(Receipt receipt,List<WorldProblem> problems) { return new ScalarHistogram(receipt,Optional.empty(),new int[0],Optional.empty(),problems,0); }
    private static final class Scan {
        int present,below,above,inRange;double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY;
        void observe(double value) { present++;minimum=Math.min(minimum,value);maximum=Math.max(maximum,value); }
    }
    private static double value(Input input,int at) {
        double value=input.reader().value(at);if(!Double.isFinite(value))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Scalar histogram source contains a nonfinite present value");return value;
    }
    private static ScalarHistogram execute(Input input,Optional<Range> requested,Limits limits,BooleanSupplier cancelled) {
        if(!input.problems().isEmpty())return failed(input.receipt(),input.problems());long payload=payload(input.receipt(),limits);
        if(input.size()>limits.samples()||input.size()>limits.visits()||payload>limits.payloadBytes()||input.bytes()+payload>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar histogram source/sample/payload/working allowance reached");
        var scan=new Scan();var range=requested;long visits=input.size();
        if(requested.isEmpty()) {
            for(int at=0;at<input.size();at++) { WorldProblem.check(cancelled);if(input.reader().present(at))scan.observe(value(input,at)); }
            if(scan.present>0)range=Optional.of(new Range(scan.minimum,scan.maximum));
            if(range.isPresent()) { if(input.size()>limits.visits()-visits)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Automatic histogram range requires another complete source pass");visits+=input.size(); }
        }
        int[] counts=new int[limits.bins()];
        if(range.isPresent()) {
            var selected=range.orElseThrow();var colors=selected.style(MapRamp.GRAYSCALE);
            for(int at=0;at<input.size();at++) {
                WorldProblem.check(cancelled);if(!input.reader().present(at))continue;double value=value(input,at);if(requested.isPresent())scan.observe(value);
                if(value<selected.minimum())scan.below++;else if(value>selected.maximum())scan.above++;
                else { int bin=Math.min(counts.length-1,(int)(colors.normalize(value)*counts.length));counts[bin]++;scan.inRange++; }
            }
        }
        var summary=new Summary(scan.present,input.size()-scan.present,scan.inRange,scan.below,scan.above,scan.present==0?OptionalDouble.empty():OptionalDouble.of(scan.minimum),scan.present==0?OptionalDouble.empty():OptionalDouble.of(scan.maximum),visits);
        WorldProblem.check(cancelled);return new ScalarHistogram(input.receipt(),range,counts,Optional.of(summary),List.of(),payload);
    }
}
