package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Stable global back-to-front centroid order; not a native OIT or intersecting-surface solution. */
public final class WorldDrawOrder {
    public static final int WORKING_BYTES_PER_FACE=Long.BYTES+Double.BYTES;
    /** Actual sort work, not a wall-clock timing or native transparency guarantee. */
    public record SortStats(int faces,long comparisons,long movedPairs,boolean alreadyOrdered) {
        public SortStats { if(faces<0||comparisons<0||movedPairs<0||alreadyOrdered&&movedPairs!=0)throw new IllegalArgumentException("Invalid face sort statistics"); }
    }
    public record View(WorldSnapshot.Position anchor,double eyeX,double eyeY,double eyeZ,double targetX,double targetY,double targetZ) {
        public View { Objects.requireNonNull(anchor);double[] values={eyeX,eyeY,eyeZ,targetX,targetY,targetZ};for(double value:values)if(!Double.isFinite(value)||Math.abs(value)>1048576)throw new IllegalArgumentException("View exceeds finite relative-coordinate bounds");if(eyeX==targetX&&eyeY==targetY&&eyeZ==targetZ)throw new IllegalArgumentException("View direction is empty"); }
    }
    public record Limits(int faces,long sortBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(262144,32L*1024*1024,512L*1024*1024);
        public Limits { if(faces<0||faces>1048576||sortBytes<0||sortBytes>32L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Draw-order limits exceed renderer bounds"); }
    }
    private final WorldScene scene;private final View view;private final long[] order;private final List<WorldProblem> problems;private final Optional<SortStats> sortStats;
    private WorldDrawOrder(WorldScene scene,View view,long[] owned,List<WorldProblem> problems) { this(scene,view,owned,problems,Optional.empty()); }
    private WorldDrawOrder(WorldScene scene,View view,long[] owned,List<WorldProblem> problems,Optional<SortStats> sortStats) { this.scene=scene;this.view=view;order=owned;this.problems=List.copyOf(problems);this.sortStats=Objects.requireNonNull(sortStats);if(sortStats.isPresent()&&sortStats.orElseThrow().faces()!=owned.length)throw new IllegalArgumentException("Face sort statistics differ from output coverage");if(!problems.isEmpty()&&(owned.length!=0||sortStats.isPresent()))throw new IllegalArgumentException("Partial face ordering escaped failure"); }
    public static WorldDrawOrder prepare(WorldScene scene,View view,BooleanSupplier cancelled) { return prepare(scene,view,Limits.DEFAULT,cancelled); }
    public static WorldDrawOrder prepare(WorldScene scene,View view,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);Objects.requireNonNull(view);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(scene,view,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new WorldDrawOrder(scene,view,new long[0],List.of(failure.problem)); }
        catch(ArithmeticException overflow) { WorldProblem.check(cancelled);return new WorldDrawOrder(scene,view,new long[0],List.of(new WorldProblem(WorldProblem.Code.COORDINATE,Optional.empty(),"World/view anchor difference exceeds its exact coordinate range"))); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return new WorldDrawOrder(scene,view,new long[0],List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to sort translucent faces; no partial order was published"))); }
    }
    /** Metadata-only reservation; invalid sort requests still report their limit on the worker. */
    static long reservation(WorldScene scene,Limits limits) {
        long source=scene.usage().knownPayloadBytes(),scratch=(long)scene.usage().translucentFaces()*WORKING_BYTES_PER_FACE;
        if(source>limits.workingBytes())throw new IllegalArgumentException("Sort source scene exceeds its declared working budget");
        return Math.min(limits.workingBytes(),source+Math.min(limits.sortBytes(),scratch));
    }
    /** Constant-size pose preflight, matching the camera's overflow/underflow-safe length calculation. */
    static double directionLength(View view) { return Math.hypot(Math.hypot(view.targetX()-view.eyeX(),view.targetY()-view.eyeY()),view.targetZ()-view.eyeZ()); }
    /** Exact normalized-forward equality; translation/projection changes do not change canonical depth keys. */
    public static boolean compatibleViews(WorldScene scene,View first,View second) {
        Objects.requireNonNull(scene);Objects.requireNonNull(first);Objects.requireNonNull(second);
        double a=directionLength(first),b=directionLength(second);if(a<1e-8||b<1e-8)return false;
        if((first.targetX()-first.eyeX())/a!=(second.targetX()-second.eyeX())/b||(first.targetY()-first.eyeY())/a!=(second.targetY()-second.eyeY())/b||(first.targetZ()-first.eyeZ())/a!=(second.targetZ()-second.eyeZ())/b)return false;
        return withinRange(scene,first)&&withinRange(scene,second);
    }
    /** Complete scene partition metadata proves every section-origin offset without a synchronous scan. */
    private static boolean withinRange(WorldScene scene,View view) {
        var extent=scene.snapshot().extent();int edge=scene.plan().limits().sectionEdge();
        long lastX=Math.addExact(extent.minX(),(long)((extent.width()-1)/edge)*edge),lastY=(long)extent.minY()+(extent.height()-1)/edge*edge,lastZ=Math.addExact(extent.minZ(),(long)((extent.depth()-1)/edge)*edge);
        return bounded(extent.minX(),lastX,view.anchor().x())&&bounded(extent.minY(),lastY,view.anchor().y())&&bounded(extent.minZ(),lastZ,view.anchor().z());
    }
    private static boolean bounded(long first,long last,long anchor) {
        try { return Math.abs((double)Math.subtractExact(first,anchor))<=1048576&&Math.abs((double)Math.subtractExact(last,anchor))<=1048576; }
        catch(ArithmeticException overflow) { return false; }
    }
    /** The preparation view remains the original receipt; a compatible view reuses the same immutable keys. */
    public boolean matches(View candidate) { Objects.requireNonNull(candidate);return !requiresPlaceholder()&&compatibleViews(scene,view,candidate); }
    private static WorldDrawOrder execute(WorldScene scene,View view,Limits limits,BooleanSupplier cancelled) {
        int count=scene.usage().translucentFaces();
        if(count>limits.faces())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Translucent face count exceeds the global sorting budget");
        long bytes=(long)count*WORKING_BYTES_PER_FACE;if(bytes>limits.sortBytes()||scene.usage().knownPayloadBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Translucent order/scratch or combined CPU payload budget exceeded");
        double fx=view.targetX()-view.eyeX(),fy=view.targetY()-view.eyeY(),fz=view.targetZ()-view.eyeZ(),length=directionLength(view);if(length<1e-8)throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"View direction is too small for stable sorting");fx/=length;fy/=length;fz/=length;
        long[] keys=new long[count];double[] depths=new double[count];int at=0;var extent=scene.snapshot().extent();
        for(int section=0;section<scene.sections().size();section++) {
            WorldProblem.check(cancelled);var mesh=scene.sections().get(section);long dx=Math.subtractExact(mesh.origin().x(),view.anchor().x()),dz=Math.subtractExact(mesh.origin().z(),view.anchor().z()),dy=(long)mesh.origin().y()-view.anchor().y();
            if(Math.abs((double)dx)>1048576||Math.abs((double)dy)>1048576||Math.abs((double)dz)>1048576)throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"Section lies outside view-relative precision bounds");
            long sx=Math.subtractExact(mesh.origin().x(),extent.minX()),sy=(long)mesh.origin().y()-extent.minY(),sz=Math.subtractExact(mesh.origin().z(),extent.minZ());
            if(mesh.translucentCount()>count-at)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Section translucent count exceeds the complete scene receipt");
            for(int index=0;index<mesh.translucentCount();index++) {
                if((index&1023)==0)WorldProblem.check(cancelled);int face=mesh.translucentFace(index);
                keys[at]=(long)section<<32|face&0xffffffffL;depths[at]=(sx+mesh.translucentCenter(index,0))*fx+(sy+mesh.translucentCenter(index,1))*fy+(sz+mesh.translucentCenter(index,2))*fz;if(!Double.isFinite(depths[at]))throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"Translucent depth is not finite");at++;
            }
        }
        if(at!=count)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Translucent metadata count changed during ordering");
        var stats=WorldFaceSort.sort(keys,depths,cancelled);
        WorldProblem.check(cancelled);return new WorldDrawOrder(scene,view,keys,List.of(),Optional.of(stats));
    }
    public WorldScene scene() { return scene; }
    public View view() { return view; }
    public int size() { return order.length; }
    public long bytes() { return (long)order.length*8; }
    public Optional<SortStats> sortStats() { return sortStats; }
    public int section(int index) { return (int)(order[index]>>>32); }
    public int face(int index) { return (int)order[index]; }
    public long[] copyOrder() { return order.clone(); }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
