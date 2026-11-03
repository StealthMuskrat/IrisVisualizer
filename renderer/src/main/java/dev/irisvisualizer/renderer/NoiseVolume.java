package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached original native scalar samples, X fastest, then Z, then every configured Y. */
public final class NoiseVolume {
    public record Grid(MapSamples.Grid horizontal,MapSamples.Vertical vertical) {
        public Grid { Objects.requireNonNull(horizontal);Objects.requireNonNull(vertical);Math.multiplyExact(horizontal.count(),vertical.height()); }
        public Grid(int x,int z,MapSamples.Vertical vertical) { this(new MapSamples.Grid(x,z,128,128,1),vertical); }
        public long count() { return horizontal.count()*vertical.height(); }
        public int width() { return horizontal.width(); }
        public int depth() { return horizontal.height(); }
        public int height() { return vertical.height(); }
        public int index(int x,int y,int z) {
            if(x<0||x>=width()||y<0||y>=height()||z<0||z>=depth())throw new IndexOutOfBoundsException("Sample outside noise volume");
            return Math.toIntExact(((long)y*depth()+z)*width()+x);
        }
        public Point point(int cell) {
            if(cell<0||cell>=count())throw new IndexOutOfBoundsException("Sample outside noise volume");
            return new Point(horizontal.worldX(cell%width()),Math.toIntExact((long)vertical.minY()+cell/horizontal.count()),horizontal.worldZ(cell/width()%depth()));
        }
    }
    public record Point(double x,int y,double z) {}
    public record Limits(int horizontalDimension,int height,long samples,long payloadBytes) {
        public static final Limits DEFAULT=new Limits(4096,4096,16777216,144L*1024*1024);
        public Limits { if(horizontalDimension<1||horizontalDimension>4096||height<1||height>4096||samples<1||samples>16777216||payloadBytes<0||payloadBytes>144L*1024*1024)throw new IllegalArgumentException("Scalar volume limits exceed renderer bounds"); }
    }
    public record Summary(int present,int absent,OptionalDouble minimum,OptionalDouble maximum) {
        public Summary { Objects.requireNonNull(minimum);Objects.requireNonNull(maximum); }
    }
    private final UUID id=UUID.randomUUID();private final MapSamples.Context context;private final Grid grid;private final double[] values;private final byte[] present;private final Optional<Summary> summary;private final List<WorldProblem> problems;
    private NoiseVolume(MapSamples.Context context,Grid grid,double[] values,byte[] present,Optional<Summary> summary,List<WorldProblem> problems) {
        this.context=context;this.grid=grid;this.values=values;this.present=present;this.summary=summary;this.problems=List.copyOf(problems);
    }
    static void preflight(MapSamples.Context context,Grid grid,Limits limits) {
        if(!context.world().minecraftVersion().equals(BlockModels.PROFILE))throw WorldProblem.fail(WorldProblem.Code.VERSION,"Native scalar volume requires the pinned Minecraft profile");
        if(grid.height()>limits.height())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Full configured height exceeds the scalar volume dimension limit; no Y samples were omitted");
        if(grid.width()>limits.horizontalDimension()||grid.depth()>limits.horizontalDimension()||grid.count()>limits.samples()||grid.count()*9>limits.payloadBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar volume exceeds its full-height sample/payload budget; explicitly reduce horizontal sample count");
    }
    /** Caller freezes both arrays until this complete validating copy returns. Absent values are not invented. */
    public static NoiseVolume capture(MapSamples.Context context,Grid grid,double[] values,byte[] present,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(grid);Objects.requireNonNull(values);Objects.requireNonNull(present);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try {
            preflight(context,grid,limits);if(values.length!=grid.count()||present.length!=grid.count())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Provide one scalar/presence flag for every full-height sample");
            var copy=new double[values.length];var coverage=new byte[present.length];int count=0;double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY;
            for(int at=0;at<copy.length;at++) {
                if((at&4095)==0)WorldProblem.check(cancelled);byte flag=present[at];if(flag!=0&&flag!=1)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Scalar presence must contain zero/one only");coverage[at]=flag;if(flag==0)continue;
                double value=values[at];if(!Double.isFinite(value))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Native scalar volume contains a nonfinite present sample");copy[at]=value;count++;minimum=Math.min(minimum,value);maximum=Math.max(maximum,value);
            }
            var summary=new Summary(count,copy.length-count,count==0?OptionalDouble.empty():OptionalDouble.of(minimum),count==0?OptionalDouble.empty():OptionalDouble.of(maximum));WorldProblem.check(cancelled);
            return new NoiseVolume(context,grid,copy,coverage,Optional.of(summary),List.of());
        }catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(context,grid,failure.problem); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(context,grid,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to detach native scalar volume; no partial samples published")); }
    }
    static NoiseVolume failed(MapSamples.Context context,Grid grid,WorldProblem problem) { return new NoiseVolume(context,grid,new double[0],new byte[0],Optional.empty(),List.of(problem)); }
    public UUID id() { return id; }
    public MapSamples.Context context() { return context; }
    public Grid grid() { return grid; }
    public int size() { return values.length; }
    public long payloadBytes() { return (long)values.length*8+present.length; }
    public boolean present(int cell) { if(requiresPlaceholder())throw new IllegalStateException("Scalar volume capture failed");return present[cell]!=0; }
    public double value(int cell) { if(!present(cell))throw new IllegalStateException("Native scalar sample is absent");return values[cell]; }
    public Optional<Summary> summary() { return summary; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
