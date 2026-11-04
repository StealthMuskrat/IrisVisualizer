package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Stable global back-to-front centroid order; not a native OIT or intersecting-surface solution. */
public final class WorldDrawOrder {
    public record View(WorldSnapshot.Position anchor,double eyeX,double eyeY,double eyeZ,double targetX,double targetY,double targetZ) {
        public View { Objects.requireNonNull(anchor);double[] values={eyeX,eyeY,eyeZ,targetX,targetY,targetZ};for(double value:values)if(!Double.isFinite(value)||Math.abs(value)>1048576)throw new IllegalArgumentException("View exceeds finite relative-coordinate bounds");if(eyeX==targetX&&eyeY==targetY&&eyeZ==targetZ)throw new IllegalArgumentException("View direction is empty"); }
    }
    public record Limits(int faces,long sortBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(262144,32L*1024*1024,512L*1024*1024);
        public Limits { if(faces<0||faces>1048576||sortBytes<0||sortBytes>32L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Draw-order limits exceed renderer bounds"); }
    }
    private final WorldScene scene;private final View view;private final long[] order;private final List<WorldProblem> problems;
    private WorldDrawOrder(WorldScene scene,View view,long[] owned,List<WorldProblem> problems) { this.scene=scene;this.view=view;order=owned;this.problems=List.copyOf(problems);if(!problems.isEmpty()&&owned.length!=0)throw new IllegalArgumentException("Partial face ordering escaped failure"); }
    public static WorldDrawOrder prepare(WorldScene scene,View view,BooleanSupplier cancelled) { return prepare(scene,view,Limits.DEFAULT,cancelled); }
    public static WorldDrawOrder prepare(WorldScene scene,View view,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);Objects.requireNonNull(view);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(scene,view,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new WorldDrawOrder(scene,view,new long[0],List.of(failure.problem)); }
        catch(ArithmeticException overflow) { WorldProblem.check(cancelled);return new WorldDrawOrder(scene,view,new long[0],List.of(new WorldProblem(WorldProblem.Code.COORDINATE,Optional.empty(),"World/view anchor difference exceeds its exact coordinate range"))); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return new WorldDrawOrder(scene,view,new long[0],List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to sort translucent faces; no partial order was published"))); }
    }
    private static WorldDrawOrder execute(WorldScene scene,View view,Limits limits,BooleanSupplier cancelled) {
        int count=0;
        for(var mesh:scene.sections()) { WorldProblem.check(cancelled);if(mesh.translucentCount()>limits.faces()-count)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Translucent face count exceeds the global sorting budget");count+=mesh.translucentCount(); }
        long bytes=(long)count*32;if(bytes>limits.sortBytes()||scene.usage().knownPayloadBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Translucent order/scratch or combined CPU payload budget exceeded");
        long[] keys=new long[count],scratchKeys=new long[count];double[] depths=new double[count],scratchDepths=new double[count];
        double fx=view.targetX()-view.eyeX(),fy=view.targetY()-view.eyeY(),fz=view.targetZ()-view.eyeZ(),length=Math.sqrt(fx*fx+fy*fy+fz*fz);if(length<1e-8)throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"View direction is too small for stable sorting");fx/=length;fy/=length;fz/=length;int at=0;
        for(int section=0;section<scene.sections().size();section++) {
            WorldProblem.check(cancelled);var mesh=scene.sections().get(section);long dx=Math.subtractExact(mesh.origin().x(),view.anchor().x()),dz=Math.subtractExact(mesh.origin().z(),view.anchor().z()),dy=(long)mesh.origin().y()-view.anchor().y();
            if(Math.abs((double)dx)>1048576||Math.abs((double)dy)>1048576||Math.abs((double)dz)>1048576)throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"Section lies outside view-relative precision bounds");
            for(int index=0;index<mesh.translucentCount();index++) {
                if((index&1023)==0)WorldProblem.check(cancelled);int face=mesh.translucentFace(index);
                keys[at]=(long)section<<32|face&0xffffffffL;depths[at]=(dx+mesh.translucentCenter(index,0)-view.eyeX())*fx+(dy+mesh.translucentCenter(index,1)-view.eyeY())*fy+(dz+mesh.translucentCenter(index,2)-view.eyeZ())*fz;if(!Double.isFinite(depths[at]))throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"Translucent depth is not finite");at++;
            }
        }
        if(at!=count)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Translucent metadata count changed during ordering");
        for(int width=1;width<count;width*=2) {
            WorldProblem.check(cancelled);
            for(int start=0;start<count;start+=width*2) {
                int middle=Math.min(start+width,count),end=Math.min(start+width*2,count),left=start,right=middle;
                for(int out=start;out<end;out++) { if((out&4095)==0)WorldProblem.check(cancelled);boolean takeLeft=left<middle&&(right>=end||depths[left]>depths[right]||depths[left]==depths[right]&&keys[left]<=keys[right]);int index=takeLeft?left++:right++;scratchKeys[out]=keys[index];scratchDepths[out]=depths[index]; }
            }
            long[] swapKeys=keys;keys=scratchKeys;scratchKeys=swapKeys;double[] swapDepths=depths;depths=scratchDepths;scratchDepths=swapDepths;
        }
        WorldProblem.check(cancelled);return new WorldDrawOrder(scene,view,keys,List.of());
    }
    public WorldScene scene() { return scene; }
    public View view() { return view; }
    public int size() { return order.length; }
    public long bytes() { return (long)order.length*8; }
    public int section(int index) { return (int)(order[index]>>>32); }
    public int face(int index) { return (int)order[index]; }
    public long[] copyOrder() { return order.clone(); }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
