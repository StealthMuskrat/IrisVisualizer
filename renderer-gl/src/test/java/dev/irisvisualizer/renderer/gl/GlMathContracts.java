package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** Context-free display math only. No LWJGL class, window, native library or GL context is used. */
public final class GlMathContracts {
    private static int groups;
    private static final WorldSnapshot.Position ANCHOR=new WorldSnapshot.Position(9_007_199_254_740_993L,-64,-9_007_199_254_740_993L);
    private GlMathContracts() {}
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void equal(Object expected,Object actual,String message) { check(Objects.equals(expected,actual),message+": expected "+expected+", got "+actual); }
    private static void near(double expected,double actual,double tolerance,String message) { check(Double.isFinite(actual)&&Math.abs(expected-actual)<=tolerance,message+": expected "+expected+", got "+actual); }
    private static <T extends Throwable> void throwsType(Class<T> type,Runnable action,String message) {
        try { action.run(); }catch(Throwable failure) { if(type.isInstance(failure))return;throw new AssertionError(message+": wrong exception",failure); }
        throw new AssertionError(message+": exception missing");
    }
    private static void group(String name,Runnable action) { action.run();groups++;System.out.println("PASS "+name); }
    private static GlCamera axial() { return new GlCamera(ANCHOR,0,0,5,0,0,0,90,1,9); }
    private static GlOrbitCamera orbit() { return new GlOrbitCamera(ANCHOR,0,0,0,10,0,0,90,0.1,1000); }
    private static double dot(double[] values,int a,int b) { return values[a]*values[b]+values[a+1]*values[b+1]+values[a+2]*values[b+2]; }
    /** Direct homogeneous corner multiplication, independent of frustum plane extraction. */
    private static double[] clip(float[] matrix,double x,double y,double z) {
        var result=new double[4];for(int row=0;row<4;row++)result[row]=matrix[row]*x+matrix[row+4]*y+matrix[row+8]*z+matrix[row+12];return result;
    }
    private static void basis() {
        for(var camera:List.of(axial(),new GlCamera(ANCHOR,1,2,3,-4,5,-6,60,0.1,100),new GlCamera(ANCHOR,0,0,0,0,1,0,60,0.1,100),new GlCamera(ANCHOR,0,0,0,0,-1,0,60,0.1,100))) {
            var b=camera.basis();for(int at=0;at<9;at+=3)near(1,dot(b,at,at),1e-12,"unit basis axis");near(0,dot(b,0,3),1e-12,"forward/right orthogonal");near(0,dot(b,0,6),1e-12,"forward/up orthogonal");near(0,dot(b,3,6),1e-12,"right/up orthogonal");
            near(b[6],b[4]*b[2]-b[5]*b[1],1e-12,"right cross forward X");near(b[7],b[5]*b[0]-b[3]*b[2],1e-12,"right cross forward Y");near(b[8],b[3]*b[1]-b[4]*b[0],1e-12,"right cross forward Z");
        }
    }
    private static void projection() {
        var matrix=axial().matrix(2);double[] nearPoint=clip(matrix,0,0,4),farPoint=clip(matrix,0,0,-4),center=clip(matrix,0,0,0);
        near(-1,nearPoint[2]/nearPoint[3],1e-6,"analytic near NDC");near(1,farPoint[2]/farPoint[3],1e-6,"analytic far NDC");near(0,center[0],1e-6,"target horizontal center");near(0,center[1],1e-6,"target vertical center");
        var corner=clip(matrix,2,1,4);near(1,corner[0]/corner[3],1e-6,"90-degree horizontal near edge at aspect two");near(1,corner[1]/corner[3],1e-6,"90-degree vertical near edge");
    }
    private static void analyticRays() {
        var camera=axial();var center=camera.ray(100,50,200,100);near(0,center.dx(),1e-12,"center ray X");near(0,center.dy(),1e-12,"center ray Y");near(-1,center.dz(),1e-12,"center ray forward");near(1,center.minimum(),1e-12,"center near distance");near(9,center.maximum(),1e-12,"center far distance");
        var corner=camera.ray(0,0,200,100);double length=Math.sqrt(6);near(-2/length,corner.dx(),1e-12,"analytic corner X");near(1/length,corner.dy(),1e-12,"top-left positive up");near(-1/length,corner.dz(),1e-12,"analytic corner Z");near(length,corner.minimum(),1e-12,"oblique near distance");near(9*length,corner.maximum(),1e-11,"oblique far distance");equal(ANCHOR,corner.anchor(),"ray exact anchor");
    }
    private static void rayProjection() {
        var camera=new GlCamera(ANCHOR,7,4,12,-1,2,0,67,0.05,500);int width=400,height=300;var matrix=camera.matrix((double)width/height);
        for(double x:new double[]{0.5,199.5,399.5})for(double y:new double[]{0.5,149.5,299.5}) {
            var ray=camera.ray(x,y,width,height);near(1,Math.hypot(Math.hypot(ray.dx(),ray.dy()),ray.dz()),1e-12,"normalized pointer ray");
            double distance=(ray.minimum()+ray.maximum())/2;var point=clip(matrix,ray.x()+ray.dx()*distance,ray.y()+ray.dy()*distance,ray.z()+ray.dz()*distance);
            near(x,(point[0]/point[3]+1)*width/2,1e-3,"ray/matrix horizontal round trip");near(y,(1-point[1]/point[3])*height/2,1e-3,"ray/matrix top-left round trip");
            for(boolean far:new boolean[]{false,true}) { double t=far?ray.maximum():ray.minimum();var endpoint=clip(matrix,ray.x()+ray.dx()*t,ray.y()+ray.dy()*t,ray.z()+ray.dz()*t);near(far?1:-1,endpoint[2]/endpoint[3],1e-3,"ray clipping matches projection planes"); }
        }
    }
    private static void offsets() {
        var camera=axial();float[] values={99,0,0,0,99};camera.offset(new WorldSnapshot.Position(ANCHOR.x()+7,ANCHOR.y()+4,ANCHOR.z()-3),values,1);
        equal(7f,values[1],"exact X subtraction before float");equal(4f,values[2],"exact Y offset");equal(-3f,values[3],"exact Z subtraction before float");equal(99f,values[0],"offset prefix untouched");equal(99f,values[4],"offset suffix untouched");
        var before=values.clone();throwsType(GlFailure.class,()->camera.offset(new WorldSnapshot.Position(ANCHOR.x()+1_048_577,ANCHOR.y(),ANCHOR.z()),values,1),"precision bound rejection");check(Arrays.equals(before,values),"failed offset writes no partial data");
        throwsType(GlFailure.class,()->camera.offset(new WorldSnapshot.Position(Long.MIN_VALUE,ANCHOR.y(),ANCHOR.z()),values,1),"anchor overflow rejection");
    }
    private static void navigation() {
        var original=orbit();var axial=original.camera();near(0,axial.eyeX(),1e-12,"zero-yaw eye X");near(10,axial.eyeZ(),1e-12,"zero-yaw eye Z");var turned=original.orbit(90,0).camera();near(10,turned.eyeX(),1e-12,"quarter orbit X");near(0,turned.eyeZ(),1e-12,"quarter orbit Z");
        var panned=original.pan(10,20,100);near(-2,panned.centerX(),1e-12,"analytic horizontal pan");near(4,panned.centerY(),1e-12,"analytic vertical pan");near(0,panned.centerZ(),1e-12,"pan keeps view plane");near(10,panned.distance(),1e-12,"pan distance unchanged");equal(ANCHOR,panned.anchor(),"pan anchor unchanged");
        near(89.9,original.orbit(0,1000).pitch(),1e-12,"upper pitch clamp");near(-89.9,original.orbit(0,-1000).pitch(),1e-12,"lower pitch clamp");check(original.dolly(Double.MIN_VALUE).distance()>original.near(),"dolly stays beyond near plane");check(original.dolly(Double.MAX_VALUE).distance()<original.far(),"dolly stays within far plane");near(20,original.dolly(2).distance(),1e-12,"ordinary dolly");
    }
    private static void rebase() {
        var original=new GlOrbitCamera(ANCHOR,0.25,2.5,-0.75,10,23,17,60,0.1,1000);var next=new WorldSnapshot.Position(ANCHOR.x()+5,ANCHOR.y()-3,ANCHOR.z()-7);var rebased=original.rebase(next);
        near(-4.75,rebased.centerX(),1e-12,"rebased fractional X");near(5.5,rebased.centerY(),1e-12,"rebased Y");near(6.25,rebased.centerZ(),1e-12,"rebased fractional Z");var a=original.camera();var b=rebased.camera();near(a.eyeX(),b.eyeX()+5,1e-12,"same world-relative eye X");near(a.eyeY(),b.eyeY()-3,1e-12,"same eye Y");near(a.eyeZ(),b.eyeZ()-7,1e-12,"same eye Z");
        equal(original,rebased.rebase(ANCHOR),"exact round trip rebase");throwsType(ArithmeticException.class,()->original.rebase(new WorldSnapshot.Position(Long.MIN_VALUE,0,0)),"overflow rebase explicit");
    }
    private static void guards() {
        throwsType(IllegalArgumentException.class,()->axial().matrix(0),"zero aspect");throwsType(IllegalArgumentException.class,()->axial().matrix(Double.NaN),"nonfinite aspect");throwsType(GlFailure.class,()->new GlCamera(ANCHOR,0,0,0,0,0,0,60,0.1,10).basis(),"empty viewing direction");
        throwsType(IllegalArgumentException.class,()->axial().ray(200,50,200,100),"half-open horizontal viewport");throwsType(IllegalArgumentException.class,()->axial().ray(0,100,200,100),"half-open vertical viewport");throwsType(IllegalArgumentException.class,()->axial().ray(Double.NaN,0,200,100),"nonfinite pointer");
        throwsType(IllegalArgumentException.class,()->orbit().dolly(0),"nonpositive dolly");throwsType(IllegalArgumentException.class,()->orbit().pan(0,0,0),"invalid pan viewport");throwsType(IllegalArgumentException.class,()->new GlFrustum(new float[15]),"wrong matrix length");var invalid=new float[16];invalid[0]=Float.NaN;throwsType(GlFailure.class,()->new GlFrustum(invalid),"nonfinite frustum");
    }
    private static BlockMesh.Bounds box(float minX,float minY,float minZ,float maxX,float maxY,float maxZ) { return new BlockMesh.Bounds(new ModelPlan.Vec3(minX,minY,minZ),new ModelPlan.Vec3(maxX,maxY,maxZ)); }
    private static float[] identity() { return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}; }
    private static void canonicalFrustum() {
        var frustum=new GlFrustum(identity());float[] offset={0,0,0};check(frustum.visible(box(-0.5f,-0.5f,-0.5f,0.5f,0.5f,0.5f),offset,0),"canonical inner box");check(frustum.visible(box(1,0,0,1,0,0),offset,0),"closed plane boundary");check(frustum.visible(box(1.000001f,0,0,1.000001f,0,0),offset,0),"outward numerical allowance");
        check(!frustum.visible(box(2,0,0,3,1,1),offset,0),"canonical outside right");check(!frustum.visible(box(0,0,-3,1,1,-2),offset,0),"canonical outside depth");check(!frustum.visible(box(0,0,0,1,1,1),new float[]{4,0,0},0),"translated bounds rejected");
    }
    private static void frustumOracle() {
        var matrix=new GlCamera(ANCHOR,2,3,8,0,0,0,60,0.1,100).matrix(1.6);var frustum=new GlFrustum(matrix);var random=new Random(0x4652555354554dL);int checks=0;
        for(int fixture=0;fixture<256;fixture++) {
            float x=random.nextFloat()*40-20,y=random.nextFloat()*40-20,z=random.nextFloat()*40-20;var box=box(x,y,z,x+0.1f+random.nextFloat()*2,y+0.1f+random.nextFloat()*2,z+0.1f+random.nextFloat()*2);double[] maximum=new double[6];Arrays.fill(maximum,Double.NEGATIVE_INFINITY);
            for(int corner=0;corner<8;corner++) { var c=clip(matrix,(corner&1)==0?box.min().x():box.max().x(),(corner&2)==0?box.min().y():box.max().y(),(corner&4)==0?box.min().z():box.max().z());for(int axis=0;axis<3;axis++) { maximum[axis*2]=Math.max(maximum[axis*2],c[3]+c[axis]);maximum[axis*2+1]=Math.max(maximum[axis*2+1],c[3]-c[axis]); } }
            boolean outside=false,decisivelyOutside=false;for(double value:maximum) { outside|=value<0;decisivelyOutside|=value< -0.01; }
            boolean visible=frustum.visible(box,new float[3],0);if(!outside) { check(visible,"corner oracle cannot lose a potentially visible box");checks++; }else if(decisivelyOutside) { check(!visible,"separated corner oracle outside box");checks++; }
        }
        check(checks>200,"oracle exercises broad separated fixtures");
    }
    private static NoiseSurface surface(boolean selected) {
        var context=new MapSamples.Context(new WorldSnapshot.Source(UUID.fromString("00000000-0000-0000-0000-000000000002"),"0".repeat(64),"synthetic-camera-contracts",BlockModels.PROFILE,AssetId.minecraft("overworld"),0),"test","camera","supplied scalar data");
        var grid=new NoiseVolume.Grid(new MapSamples.Grid(-37,91,8,4,0.5),new MapSamples.Vertical(-16,4));var flags=new byte[128];Arrays.fill(flags,(byte)1);var values=new double[128];if(selected)Arrays.fill(values,1);
        var volume=NoiseVolume.capture(context,grid,values,flags,NoiseVolume.Limits.DEFAULT,()->false);var style=new NoiseSurface.Style(1,1,new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE));var result=NoiseSurface.prepare(volume,style,NoiseSurface.Limits.DEFAULT,()->false);check(!result.requiresPlaceholder(),"complete camera fixture");return result;
    }
    private static void scalarBatchClipping() {
        var surface=surface(true);equal(1,surface.batchCount(),"small uniform source has one range");float[] offset={0,0,0};float[] wide=identity();wide[0]=wide[5]=wide[10]=0.1f;var frustum=new GlFrustum(wide);
        check(frustum.visible(surface,0,0.5f,offset),"whole range visible");check(!frustum.visible(surface,0,0.5f,new float[]{30,0,0}),"whole range outside view");
        check(frustum.visible(surface,0,0.5f,offset,new WorldClip.Bounds(4,0,0,5,4,2)),"closed clipping boundary retains touching range");check(!frustum.visible(surface,0,0.5f,offset,new WorldClip.Bounds(4.01,0,0,5,4,2)),"disjoint clipping range rejected");
    }
    private static void scalarFit() {
        var source=surface(true);var fit=GlOrbitCamera.fit(source,2);equal(new WorldSnapshot.Position(-37,-16,91),fit.anchor(),"fit exact native anchor");near(2,fit.centerX(),1e-12,"full X center");near(2,fit.centerY(),1e-12,"full height center");near(1,fit.centerZ(),1e-12,"full Z center");var matrix=fit.camera().matrix(2);
        for(int corner=0;corner<8;corner++) { var p=clip(matrix,(corner&1)==0?0:4,(corner&2)==0?0:4,(corner&4)==0?0:2);check(p[3]>0,"fitted corner ahead of eye");for(int axis=0;axis<3;axis++)check(Math.abs(p[axis]/p[3])<=1.00001,"fitted corner lies inside view"); }
        equal(fit,GlOrbitCamera.fit(surface(false),2),"empty surface uses full configured extent");
    }
    private static void clippedFit() {
        var source=surface(true);var full=GlOrbitCamera.fit(source,1);var clip=new WorldClip(new WorldSnapshot.Extent(-36,-15,91,2,2,1));var fit=GlOrbitCamera.fit(source,1,clip);near(2,fit.centerX(),1e-12,"clipped X center");near(2,fit.centerY(),1e-12,"clipped Y center");near(0.5,fit.centerZ(),1e-12,"clipped Z center");check(fit.distance()<full.distance(),"smaller clipped volume fits closer");equal(full.anchor(),fit.anchor(),"clipped fit retains source anchor");
        var touching=GlOrbitCamera.fit(source,1,new WorldClip(new WorldSnapshot.Extent(-33,-16,91,1,4,2)));near(4,touching.centerX(),1e-12,"closed boundary intersection fits plane");throwsType(GlFailure.class,()->GlOrbitCamera.fit(source,1,new WorldClip(new WorldSnapshot.Extent(100,100,100,1,1,1))),"nonintersecting clip explicit failure");equal(full,GlOrbitCamera.fit(source,1,WorldClip.NONE),"unbounded overload preserves full-source fit");
    }
    public static void main(String[] arguments) {
        if(arguments.length!=0)throw new IllegalArgumentException("No arguments supported");
        group("orthonormal and vertical camera bases",GlMathContracts::basis);
        group("analytic perspective projection",GlMathContracts::projection);
        group("analytic center and corner rays",GlMathContracts::analyticRays);
        group("pointer projection round trips",GlMathContracts::rayProjection);
        group("exact anchor-relative float offsets",GlMathContracts::offsets);
        group("orbit pan and dolly navigation",GlMathContracts::navigation);
        group("exact anchor rebasing",GlMathContracts::rebase);
        group("camera pointer and frustum guards",GlMathContracts::guards);
        group("canonical frustum boundaries",GlMathContracts::canonicalFrustum);
        group("independent homogeneous-corner culling",GlMathContracts::frustumOracle);
        group("scalar range and clip rejection",GlMathContracts::scalarBatchClipping);
        group("full and empty scalar fitting",GlMathContracts::scalarFit);
        group("clipped scalar fitting",GlMathContracts::clippedFit);
        System.out.println("PASS "+groups+" context-free display math groups; no OpenGL/native parity is claimed.");
    }
}
