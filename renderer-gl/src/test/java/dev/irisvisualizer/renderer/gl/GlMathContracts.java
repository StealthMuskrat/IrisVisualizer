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
    private static void scalarFocus() {
        for(var origin:new int[][]{{Integer.MAX_VALUE-8,Integer.MIN_VALUE+8},{Integer.MIN_VALUE+8,Integer.MAX_VALUE-8}})for(var meshing:NoiseSurface.Meshing.values())for(double scale:new double[]{0.1,1e-8}) {
            var grid=new NoiseVolume.Grid(new MapSamples.Grid(origin[0],origin[1],2,2,scale),new MapSamples.Vertical(-64,2));var flags=new byte[]{1,1,1,1,1,1,1,1};
            var volume=NoiseVolume.capture(surface(true).volume().context(),grid,new double[]{1,1,1,1,1,1,1,1},flags,NoiseVolume.Limits.DEFAULT,()->false);var source=NoiseSurface.prepare(volume,new NoiseSurface.Style(1,1,new MapRaster.ScalarStyle(0,2,MapRamp.GRAYSCALE),meshing),NoiseSurface.Limits.DEFAULT,()->false);check(!source.requiresPlaceholder(),"complete distant-focus surface");
            var anchor=new WorldSnapshot.Position(origin[0],-64,origin[1]);var hit=NoisePick.query(source,new WorldRay(anchor,2,1.5,1.5*scale,-1,0,0,0,10),source.size(),()->false).hit().orElseThrow();check(hit.grid().orElseThrow()==grid,"query keeps exact immutable grid metadata");equal(7,hit.sample().sample(),"focus uses original covered sample");equal(volume.id(),hit.sample().volumeId(),"focus retains source volume identity");
            if(scale<1e-7) { equal((double)origin[0],hit.sample().point().x(),"tiny-spacing absolute X is already rounded in native point metadata");equal((double)origin[1],hit.sample().point().z(),"tiny-spacing absolute Z is already rounded in native point metadata"); }
            var original=new GlOrbitCamera(anchor,1,2,3,10,23,17,60,0.1,1000);var focused=original.focus(hit);near(2*scale,focused.centerX(),1e-12,"focus preserves fractional X before absolute rounding");near(1.5,focused.centerY(),1e-12,"focus preserves exact vertical coordinate");near(1.5*scale,focused.centerZ(),1e-12,"focus preserves fractional Z before absolute rounding");equal(anchor,focused.anchor(),"focus retains camera anchor");near(original.distance(),focused.distance(),0,"focus retains orbit distance");near(original.yaw(),focused.yaw(),0,"focus retains yaw");near(original.pitch(),focused.pitch(),0,"focus retains pitch");near(original.fieldOfView(),focused.fieldOfView(),0,"focus retains FOV");near(original.near(),focused.near(),0,"focus retains near plane");near(original.far(),focused.far(),0,"focus retains far plane");
            var next=new WorldSnapshot.Position(anchor.x()+1,anchor.y()+1,anchor.z()-1);var rebased=focused.rebase(next);near(focused.centerX(),rebased.centerX()+1,1e-12,"focused X survives rebase");near(focused.centerY(),rebased.centerY()+1,1e-12,"focused Y survives rebase");near(focused.centerZ(),rebased.centerZ()-1,1e-12,"focused Z survives rebase");
            var legacy=new NoisePick.Hit(hit.face(),hit.sample(),hit.distance(),hit.localX(),hit.localY(),hit.localZ());check(legacy.grid().isEmpty(),"legacy hit constructor remains available");var compatible=original.focus(legacy);near(hit.sample().point().x()-anchor.x()+hit.localX(),compatible.centerX(),1e-12,"legacy focus uses supplied absolute point");near(hit.sample().point().z()-anchor.z()+hit.localZ(),compatible.centerZ(),1e-12,"legacy Z compatibility");
            var foreign=new NoiseVolume.Grid(new MapSamples.Grid(origin[0]-1,origin[1],2,2,scale),grid.vertical());throwsType(IllegalArgumentException.class,()->new NoisePick.Hit(hit.face(),hit.sample(),hit.distance(),hit.localX(),hit.localY(),hit.localZ(),Optional.of(foreign)),"mismatched grid metadata rejected");
            if(origin[0]>0) { var distant=new GlOrbitCamera(new WorldSnapshot.Position(Long.MIN_VALUE,-64,origin[1]),0,0,0,10,0,0,60,0.1,1000);throwsType(ArithmeticException.class,()->distant.focus(hit),"focus anchor overflow remains explicit"); }
        }
    }
    private static void navigation() {
        scalarFocus();
        var signed=new GlOrbitCamera(ANCHOR,-0.0,-0.0,-0.0,10,-0.0,-0.0,60,0.1,1000);
        check(signed.orbit(0,-0.0)==signed,"zero orbit retains exact camera identity");check(signed.pan(0,-0.0,100)==signed,"zero camera pan preserves signed-zero centers");check(signed.dolly(1)==signed,"identity dolly retains exact camera");check(signed.rebase(new WorldSnapshot.Position(ANCHOR.x(),ANCHOR.y(),ANCHOR.z()))==signed,"equal anchor rebase retains exact camera");
        throwsType(IllegalArgumentException.class,()->signed.pan(0,0,0),"zero pan still validates viewport");throwsType(IllegalArgumentException.class,()->signed.orbit(0,Double.NaN),"identity orbit does not bypass finite guards");throwsType(NullPointerException.class,()->signed.rebase(null),"identity rebase requires anchor");
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
    private static void worldGroups() {
        for(boolean mixed:new boolean[]{false,true})try(var fixture=WorldGpuFixture.culling(mixed)) {
            var scene=fixture.scene();var origin=scene.snapshot().extent().position(0);var camera=new GlCamera(origin,16,18,48,16,0.5,28,60,0.1,128);var clip=new WorldClip(new WorldSnapshot.Extent(origin.x(),origin.y(),Math.addExact(origin.z(),24),32,1,8));
            equal(1,scene.sections().size(),"one complete 32x32 synthetic section");equal(6144,scene.usage().faces(),"six faces for every explicitly uncullled fixture cell");equal(48,scene.usage().faceBatches(),"complete 128-face group coverage");equal(1093L,GlWorldCulling.workingBytes(scene,true),"admitted matrix/section/index/cache payload");equal(1037L,GlWorldCulling.workingBytes(scene,false),"no range cache in disabled mode");
            var culling=new GlWorldCulling(scene,camera,1,clip,GlCullingMode.LINEAR);equal(1,culling.visible,"clipped section remains drawable");equal(0,culling.tests(),"face decisions are lazy");var mesh=scene.sections().getFirst();boolean[] visible=new boolean[mesh.faces().size()];int guaranteedOutside=0;
            for(int face=0;face<visible.length;face++) { visible[face]=culling.face(0,face);if(mesh.faceBatchMaximum(face/WorldMesh.BATCH_FACES,2)<24) { check(!visible[face],"group strictly below the clip's Z plane must be rejected");guaranteedOutside++; } }
            check(guaranteedOutside>0&&culling.rejected()>0,"analytic outside groups were rejected");equal(48,culling.tests(),"one test per source group across a complete face walk");for(int face=visible.length-1;face>=0;face--)equal(visible[face],culling.face(0,face),"nonmonotonic material/sort traversal reuses the same decision");equal(48,culling.tests(),"reverse traversal introduces no repeated group tests");
            int inside=(28*32+16)*6+ModelPlan.Direction.UP.ordinal();check(culling.face(0,inside),"group containing the central visible top face survives");throwsType(IndexOutOfBoundsException.class,()->culling.face(-1,0),"invalid source section");throwsType(IndexOutOfBoundsException.class,()->culling.face(0,visible.length),"invalid source face");equal(48,culling.tests(),"invalid queries do not touch cache counters");
            var disabled=new GlWorldCulling(scene,camera,1,clip,false);for(int face=0;face<visible.length;face++)check(disabled.face(0,face),"disabled group rejection retains every face of a visible section");equal(0,disabled.tests(),"disabled mode allocates no decisions");
            var disjoint=new GlWorldCulling(scene,camera,1,new WorldClip(new WorldSnapshot.Extent(Math.addExact(origin.x(),64),origin.y(),origin.z(),1,1,1)),true);check(!disjoint.sections[0]&&!disjoint.face(0,0),"whole-section clip rejection precedes group work");equal(0,disjoint.tests(),"invisible section needs no face tests");throwsType(IllegalArgumentException.class,()->new GlWorldCulling(scene,camera,Double.NaN,clip,true),"invalid aspect preflight");
        }
        try(var fixture=WorldGpuFixture.empty()) { var camera=new GlCamera(fixture.scene().snapshot().extent().position(0),0.5,0.5,3,0.5,0.5,0.5,60,0.1,20);var empty=new GlWorldCulling(fixture.scene(),camera,1,WorldClip.NONE,true);equal(1,empty.empty,"empty section retains no dummy face group");equal(0,empty.visible,"empty section is not drawable");equal(0,empty.tests(),"empty scene needs no face test"); }
    }
    private static void worldHierarchicalDraw() {
        var random=new Random(0x574f524c44L);int interior=0;
        for(boolean mixed:new boolean[]{false,true})try(var fixture=WorldGpuFixture.culling(mixed)) {
            var scene=fixture.scene();var mesh=scene.sections().getFirst();var origin=mesh.origin();int count=mesh.faceBatchCount();
            for(int trial=0;trial<64;trial++) {
                int shiftX=random.nextInt(513)-256,shiftY=random.nextInt(513)-256,shiftZ=random.nextInt(513)-256;
                var anchor=new WorldSnapshot.Position(Math.addExact(origin.x(),shiftX),origin.y()+shiftY,Math.addExact(origin.z(),shiftZ));
                var camera=new GlCamera(anchor,16-shiftX+(random.nextDouble()-0.5)*64,16-shiftY+random.nextDouble()*32,64-shiftZ+random.nextDouble()*96,16-shiftX,0.5-shiftY,16-shiftZ,35+random.nextDouble()*70,0.1,512);double aspect=0.5+random.nextDouble()*2;
                var scope=trial%3==0?WorldClip.NONE:new WorldClip(new WorldSnapshot.Extent(Math.addExact(origin.x(),random.nextInt(24)),origin.y(),Math.addExact(origin.z(),random.nextInt(24)),8,1,8));
                var tree=new GlWorldCulling(scene,camera,aspect,scope,GlCullingMode.HIERARCHY);var linear=new GlWorldCulling(scene,camera,aspect,scope,GlCullingMode.LINEAR);var none=new GlWorldCulling(scene,camera,aspect,scope,GlCullingMode.NONE);var frustum=new GlFrustum(tree.matrix);
                equal(linear.visible,tree.visible,"section preflight is unchanged");equal(GlWorldCulling.workingBytes(scene,GlCullingMode.LINEAR),GlWorldCulling.workingBytes(scene,GlCullingMode.HIERARCHY),"hierarchy needs no additional draw scratch");
                for(int batch=count-1;batch>=0;batch--) {
                    int face=batch*WorldMesh.BATCH_FACES;boolean expected=tree.sections[0]&&frustum.visible(mesh,batch,tree.offsets,0,scope.enabled()?tree.clip:null);
                    equal(expected,tree.face(0,face),"hierarchy equals independent leaf predicate");equal(linear.face(0,face),tree.face(0,face),"reverse original group walk agrees with linear mode");equal(tree.sections[0],none.face(0,face),"none retains all faces after shared section culling");
                    if(!scope.enabled()&&tree.sections[0]) {
                        boolean inside=true;for(int corner=0;corner<8;corner++) {
                            float x=tree.offsets[0]+((corner&1)==0?mesh.faceBatchMinimum(batch,0):mesh.faceBatchMaximum(batch,0)),y=tree.offsets[1]+((corner&2)==0?mesh.faceBatchMinimum(batch,1):mesh.faceBatchMaximum(batch,1)),z=tree.offsets[2]+((corner&4)==0?mesh.faceBatchMinimum(batch,2):mesh.faceBatchMaximum(batch,2));var p=clip(tree.matrix,x,y,z);
                            if(p[3]<=0)inside=false;for(int axis=0;axis<3;axis++)if(Math.abs(p[axis])>p[3]-1e-3)inside=false;
                        }
                        if(inside) { interior++;check(tree.face(0,face),"independent homogeneous interior oracle survives hierarchy"); }
                    }
                }
                equal(linear.rejected(),tree.rejected(),"bulk rejected total covers exact original groups");check(tree.boundsTests()<=2*count-1&&tree.tests()<=count&&tree.tests()<=tree.boundsTests(),"bounded tree and leaf work receipts");int before=tree.boundsTests();for(int face=0;face<mesh.faces().size();face++)tree.face(0,face);equal(before,tree.boundsTests(),"material traversal never repeats prepared bounds work");
            }
            var whole=new GlCamera(origin,16,32,288,16,0.5,16,90,0.1,1024);var accepted=new GlWorldCulling(scene,whole,1,WorldClip.NONE,true);equal(1,accepted.boundsTests(),"fully contained world face hierarchy accepts at root");equal(0,accepted.tests(),"whole-parent acceptance tests no leaf");for(int face=0;face<mesh.faces().size();face++)check(accepted.face(0,face),"contained original face preserved");
            var scope=new WorldClip(new WorldSnapshot.Extent(origin.x(),origin.y(),Math.addExact(origin.z(),24),32,1,8));var partial=new GlWorldCulling(scene,whole,1,scope,true);check(partial.rejected()>0&&partial.tests()<count,"outside subtrees reduce leaf bound tests");
            throwsType(NullPointerException.class,()->new GlWorldCulling(scene,whole,1,scope,(GlCullingMode)null),"null world culling mode");throwsType(NullPointerException.class,()->GlWorldCulling.workingBytes(scene,(GlCullingMode)null),"null metadata-only sizing mode");
        }
        check(interior>100,"independent interior oracle exercises many world groups");
        try(var fixture=WorldGpuFixture.placeholder()) { var scene=fixture.scene();var camera=new GlCamera(scene.snapshot().extent().position(0),0.5,0.5,3,0.5,0.5,0.5,60,0.1,20);var markers=new GlWorldCulling(scene,camera,1,WorldClip.NONE,true);equal(1,markers.visible,"marker-only section remains drawable");equal(0,markers.boundsTests(),"no dummy face hierarchy for markers"); }
    }
    private static void markerHierarchicalDraw() {
        var random=new Random(0x4d41524b4552L);
        for(boolean mixed:new boolean[]{false,true})try(var fixture=WorldGpuFixture.markerCulling(mixed)) {
            var scene=fixture.scene();var mesh=scene.sections().getFirst();var origin=mesh.origin();int count=mesh.markerBatchCount();
            equal(mixed?512:1024,mesh.markers().size(),"complete original marker fixture");equal(1037L+8+mesh.faceBatchCount()+8+count,GlWorldCulling.workingBytes(scene,true),"exact separate face/marker prefixes and byte masks");equal(1037L,GlWorldCulling.workingBytes(scene,false),"disabled mode omits both family masks");
            for(int trial=0;trial<48;trial++) {
                var anchor=new WorldSnapshot.Position(Math.addExact(origin.x(),37),origin.y()-17,Math.addExact(origin.z(),-91));
                var camera=new GlCamera(anchor,-21+(random.nextDouble()-0.5)*48,35+random.nextDouble()*24,155+random.nextDouble()*64,-21,17.5,107,40+random.nextDouble()*50,0.1,512);double aspect=0.5+random.nextDouble()*2;
                var scope=trial%3==0?WorldClip.NONE:new WorldClip(new WorldSnapshot.Extent(Math.addExact(origin.x(),random.nextInt(24)),origin.y(),Math.addExact(origin.z(),random.nextInt(24)),8,1,8));
                var tree=new GlWorldCulling(scene,camera,aspect,scope,GlCullingMode.HIERARCHY);var linear=new GlWorldCulling(scene,camera,aspect,scope,GlCullingMode.LINEAR);var none=new GlWorldCulling(scene,camera,aspect,scope,GlCullingMode.NONE);var frustum=new GlFrustum(tree.matrix);
                for(int group=count-1;group>=0;group--) {
                    int marker=group*128;boolean expected=tree.sections[0]&&frustum.markerVisible(mesh,group,tree.offsets,0,scope.enabled()?tree.clip:null);
                    equal(expected,tree.marker(0,marker),"hierarchical marker decision equals independent leaf predicate");equal(linear.marker(0,marker),tree.marker(0,marker),"reverse marker walk agrees with linear mode");equal(tree.sections[0],none.marker(0,marker),"disabled mode keeps complete visible-section marker order");
                }
                equal(linear.markerRejected(),tree.markerRejected(),"bulk rejected count covers exact original marker groups");check(tree.markerBoundsTests()<=2*count-1&&tree.markerTests()<=count&&tree.markerBoundsTests()>=tree.markerTests(),"marker work remains structurally bounded");equal(0,none.markerBoundsTests(),"disabled markers perform no bounds work");
                int tested=tree.markerBoundsTests();for(int marker=0;marker<mesh.markers().size();marker++)equal(linear.marker(0,marker),tree.marker(0,marker),"every original marker shares its group's cached decision");equal(tested,tree.markerBoundsTests(),"forward marker traversal repeats no bound classification");
                for(int group=0;group<mesh.faceBatchCount();group++)equal(linear.face(0,group*128),tree.face(0,group*128),"marker family cannot overwrite face-family cache");
            }
            var whole=new GlCamera(origin,16,32,288,16,0.5,16,90,0.1,1024);var accepted=new GlWorldCulling(scene,whole,1,WorldClip.NONE,true);equal(1,accepted.markerBoundsTests(),"contained marker tree accepts at root");equal(0,accepted.markerTests(),"root acceptance avoids marker leaf tests");for(int marker=0;marker<mesh.markers().size();marker++)check(accepted.marker(0,marker),"root acceptance retains every original marker");
            var scope=mixed?new WorldClip(new WorldSnapshot.Extent(origin.x(),origin.y(),origin.z(),8,1,32)):new WorldClip(new WorldSnapshot.Extent(origin.x(),origin.y(),Math.addExact(origin.z(),24),32,1,8));var clipped=new GlWorldCulling(scene,whole,1,scope,true);
            if(mixed) { check(clipped.marker(0,0)&&!clipped.marker(0,128)&&clipped.marker(0,256)&&!clipped.marker(0,384),"alternating original marker groups create two retained runs");equal(2,clipped.markerRejected(),"exact alternate-group rejection count"); }
            else check(clipped.markerRejected()>0&&clipped.markerTests()<count,"clipped marker subtrees reduce leaf tests");
            throwsType(IndexOutOfBoundsException.class,()->clipped.marker(0,-1),"negative source marker index");throwsType(IndexOutOfBoundsException.class,()->clipped.marker(0,mesh.markers().size()),"source marker upper bound");throwsType(IndexOutOfBoundsException.class,()->clipped.marker(1,0),"source marker section upper bound");
            var disjoint=new GlWorldCulling(scene,whole,1,new WorldClip(new WorldSnapshot.Extent(Math.addExact(origin.x(),64),origin.y(),origin.z(),1,1,1)),true);equal(0,disjoint.markerBoundsTests(),"whole-section rejection precedes marker tree work");check(!disjoint.marker(0,0),"outside section retains no marker candidate");
        }
    }
    private static void scalarDrawPreparation() {
        var context=surface(true).volume().context();var grid=new NoiseVolume.Grid(new MapSamples.Grid(-37,91,32,32,1),new MapSamples.Vertical(-64,1));var values=new double[1024];Arrays.fill(values,0.5);var present=new byte[1024];Arrays.fill(present,(byte)1);var volume=NoiseVolume.capture(context,grid,values,present,NoiseVolume.Limits.DEFAULT,()->false);var style=new NoiseSurface.Style(0,1,new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE),NoiseSurface.Meshing.CELLS);var source=NoiseSurface.prepare(volume,style,NoiseSurface.Limits.DEFAULT,()->false);
        check(!source.requiresPlaceholder(),"complete scalar draw fixture");equal(2176,source.size(),"complete slab outer surface without height/sample omission");equal(3,source.batchCount(),"three original scalar groups");equal(1027L,GlNoiseCulling.workingBytes(source,true),"fixed math plus exact scalar mask allowance");equal(1024L,GlNoiseCulling.workingBytes(source,false),"no group mask in disabled mode");
        var origin=new WorldSnapshot.Position(-37,-64,91);var camera=new GlCamera(origin,16,18,48,16,0.5,28,60,0.1,128);var scope=new WorldClip(new WorldSnapshot.Extent(-37,-64,115,32,1,8));var enabled=new GlNoiseCulling(source,camera,1,scope,true);var disabled=new GlNoiseCulling(source,camera,1,scope,false);
        check(source.batchMaximum(0,2)<24&&!enabled.batch(0),"group strictly before scalar clip is rejected");check(enabled.visible>0&&enabled.culled>0,"partial scalar culling preserves visible groups");equal(3,enabled.visible+enabled.culled,"complete scalar group coverage");int culled=0;for(int batch=source.batchCount()-1;batch>=0;batch--) { if(!enabled.batch(batch))culled+=source.batchSize(batch);check(disabled.batch(batch),"disabled mode retains every original group"); }equal(culled,enabled.culledFaces,"culled face count covers complete original groups");equal(0,disabled.culledFaces,"disabled mode omits no CPU groups");equal(1f,enabled.scale,"original display sample scale");check(Arrays.equals(new float[3],enabled.relative),"exact native-origin offset");throwsType(IndexOutOfBoundsException.class,()->enabled.batch(-1),"invalid scalar group");throwsType(IndexOutOfBoundsException.class,()->enabled.batch(3),"scalar group upper bound");
        var outside=new GlNoiseCulling(source,camera,1,new WorldClip(new WorldSnapshot.Extent(1000,-64,1000,1,1,1)),true);equal(source.size(),outside.culledFaces,"disjoint scalar clip rejects all groups");equal(0,outside.visible,"disjoint scalar clip keeps no groups");throwsType(IllegalArgumentException.class,()->new GlNoiseCulling(source,camera,Double.NaN,scope,true),"invalid scalar aspect");var distant=new GlCamera(new WorldSnapshot.Position(origin.x()+1_048_577,origin.y(),origin.z()),16,18,48,16,0.5,28,60,0.1,128);throwsType(GlFailure.class,()->new GlNoiseCulling(source,distant,1,scope,true),"unsupported scalar camera offset");
        for(double scale:new double[]{Double.MIN_NORMAL,0.000001}) { var tinyVolume=NoiseVolume.capture(context,new NoiseVolume.Grid(new MapSamples.Grid(-37,91,1,1,scale),new MapSamples.Vertical(-64,1)),new double[]{0.5},new byte[]{1},NoiseVolume.Limits.DEFAULT,()->false);var tiny=NoiseSurface.prepare(tinyVolume,style,NoiseSurface.Limits.DEFAULT,()->false);check(!tiny.requiresPlaceholder(),"tiny source is valid detached data");var anchor=scale==Double.MIN_NORMAL?origin:new WorldSnapshot.Position(origin.x()+1_048_576,origin.y(),origin.z());var pose=new GlCamera(anchor,0.5,0.5,3,0.5,0.5,0.5,60,0.1,20);throwsType(GlFailure.class,()->new GlNoiseCulling(tiny,pose,1,WorldClip.NONE,true),"unsupported scalar float scale/spacing remains explicit"); }
        var emptyVolume=NoiseVolume.capture(context,grid,new double[1024],new byte[1024],NoiseVolume.Limits.DEFAULT,()->false);var empty=NoiseSurface.prepare(emptyVolume,style,NoiseSurface.Limits.DEFAULT,()->false);var prepared=new GlNoiseCulling(empty,camera,1,WorldClip.NONE,true);equal(1024L,GlNoiseCulling.workingBytes(empty,true),"empty scalar draw needs only fixed math allowance");equal(0,prepared.visible+prepared.culled,"empty scalar draw invents no groups");var failed=NoiseSurface.prepare(volume,style,new NoiseSurface.Limits(0,8192),()->false);throwsType(GlFailure.class,()->new GlNoiseCulling(failed,camera,1,WorldClip.NONE,true),"failed scalar source cannot prepare drawing");
    }
    private static void scalarHierarchicalDraw() {
        var context=surface(true).volume().context();var random=new Random(0x5343414c4152L);int cornerChecks=0;
        for(double scale:new double[]{0.1,0.5,1.5}) {
            var grid=new NoiseVolume.Grid(new MapSamples.Grid(-37,91,64,64,scale),new MapSamples.Vertical(-64,16));double[] values=new double[65536];byte[] flags=new byte[values.length];Arrays.fill(flags,(byte)1);
            for(int y=0;y<16;y++)for(int z=0;z<64;z++)for(int x=0;x<64;x++)values[(y*64+z)*64+x]=(x+y+z)&1;
            var volume=NoiseVolume.capture(context,grid,values,flags,NoiseVolume.Limits.DEFAULT,()->false);var origin=new WorldSnapshot.Position(-37,-64,91);double span=64*scale;
            for(var meshing:NoiseSurface.Meshing.values()) {
                var source=NoiseSurface.prepare(volume,new NoiseSurface.Style(0,1,new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE),meshing),NoiseSurface.Limits.DEFAULT,()->false);check(!source.requiresPlaceholder()&&source.batchCount()>8,"complete multi-group scalar culling source");
                var base=new GlCamera(origin,span/2,32,span+32,span/2,8,span/2,60,0.1,512);var outside=new WorldClip(new WorldSnapshot.Extent(2000,-64,2000,1,16,1));
                var rejected=new GlNoiseCulling(source,base,1,outside,GlCullingMode.HIERARCHY);var reference=new GlNoiseCulling(source,base,1,outside,GlCullingMode.LINEAR);equal(1,rejected.boundsTests,"known disjoint clip rejects entire hierarchy at root");equal(0,rejected.testedBatches,"root rejection tests no leaf bounds");equal(source.batchCount(),reference.boundsTests,"linear reference still tests all original leaves");equal(source.size(),rejected.culledFaces,"root rejection accounts for every original face");for(int batch=0;batch<source.batchCount();batch++)check(!rejected.batch(batch),"root rejection fills each group decision");
                var whole=new GlCamera(origin,span/2,32,span+256,span/2,8,span/2,90,0.1,1024);var accepted=new GlNoiseCulling(source,whole,1,WorldClip.NONE,GlCullingMode.HIERARCHY);equal(1,accepted.boundsTests,"fully contained scene accepts at the root");equal(0,accepted.testedBatches,"fully contained parent needs no leaf tests");equal(source.batchCount(),accepted.visible,"whole-parent acceptance preserves all original groups");var leafOracle=new GlFrustum(accepted.matrix);for(int batch=0;batch<source.batchCount();batch++)check(accepted.batch(batch)&&leafOracle.visible(source,batch,accepted.scale,accepted.relative),"bulk acceptance agrees with every independent leaf");
                equal(1024L+source.batchCount(),GlNoiseCulling.workingBytes(source,GlCullingMode.HIERARCHY),"hierarchy reuses admitted one-byte mask");equal(GlNoiseCulling.workingBytes(source,GlCullingMode.HIERARCHY),GlNoiseCulling.workingBytes(source,GlCullingMode.LINEAR),"linear/hierarchy preparation needs identical primitive scratch");equal(1024L,GlNoiseCulling.workingBytes(source,GlCullingMode.NONE),"disabled mode omits mask");
                for(int sample=0;sample<24;sample++) {
                    double angle=random.nextDouble()*Math.PI*2,radius=span+32+random.nextDouble()*32,aspect=0.5+random.nextDouble()*1.5;double eyeX=span/2+Math.sin(angle)*radius,eyeY=8+(random.nextDouble()-0.5)*radius,eyeZ=span/2+Math.cos(angle)*radius;
                    var anchor=sample%3==0?new WorldSnapshot.Position(origin.x()+4096,origin.y(),origin.z()-4096):origin;double dx=origin.x()-anchor.x(),dz=origin.z()-anchor.z();var camera=new GlCamera(anchor,eyeX+dx,eyeY,eyeZ+dz,span/2+dx,8,span/2+dz,35+random.nextDouble()*75,0.1,512);
                    var clip=sample%3==0?WorldClip.NONE:sample%3==1?new WorldClip(new WorldSnapshot.Extent(origin.x(),origin.y()+16,origin.z(),(int)Math.ceil(span),1,(int)Math.ceil(span))):new WorldClip(new WorldSnapshot.Extent(origin.x()+random.nextInt(32)-8,origin.y()+random.nextInt(24)-4,origin.z()+random.nextInt(32)-8,16,16,16));
                    var tree=new GlNoiseCulling(source,camera,aspect,clip,GlCullingMode.HIERARCHY);var linear=new GlNoiseCulling(source,camera,aspect,clip,GlCullingMode.LINEAR);var none=new GlNoiseCulling(source,camera,aspect,clip,GlCullingMode.NONE);var frustum=new GlFrustum(tree.matrix);var scope=clip.enabled()?clip.relative(camera.anchor()):null;int visibleFaces=0;
                    for(int batch=0;batch<source.batchCount();batch++) {
                        boolean leaf=frustum.visible(source,batch,tree.scale,tree.relative,scope);equal(leaf,tree.batch(batch),"parent rejection cannot hide a surviving independent leaf");equal(linear.batch(batch),tree.batch(batch),"hierarchy and linear decisions match for every original group");check(none.batch(batch),"disabled mode keeps every group for fragment clipping");if(tree.batch(batch))visibleFaces+=source.batchSize(batch);
                        if(!clip.enabled()) {
                            double[] maximum={-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
                            for(int corner=0;corner<8;corner++) { float x=tree.relative[0]+((corner&1)==0?source.batchMinimum(batch,0):source.batchMaximum(batch,0))*tree.scale,y=tree.relative[1]+((corner&2)==0?source.batchMinimum(batch,1):source.batchMaximum(batch,1)),z=tree.relative[2]+((corner&4)==0?source.batchMinimum(batch,2):source.batchMaximum(batch,2))*tree.scale;var point=clip(tree.matrix,x,y,z);for(int axis=0;axis<3;axis++) { maximum[axis*2]=Math.max(maximum[axis*2],point[3]+point[axis]);maximum[axis*2+1]=Math.max(maximum[axis*2+1],point[3]-point[axis]); } }
                            boolean interior=true;for(double value:maximum)interior&=value>1e-3;if(interior) { check(tree.batch(batch),"direct homogeneous corner oracle retains potentially visible group");cornerChecks++; }
                        }
                    }
                    equal(source.size(),visibleFaces+tree.culledFaces,"classified subtree coverage preserves complete source face count");equal(linear.visible,tree.visible,"linear/hierarchy visible totals agree");equal(linear.culledFaces,tree.culledFaces,"linear/hierarchy rejected face totals agree");check(tree.boundsTests<=2*source.batchCount()-1&&tree.testedBatches<=source.batchCount(),"hierarchical preparation obeys fixed source-node bound");equal(source.batchCount(),linear.testedBatches,"linear mode visits every leaf");equal(0,none.boundsTests,"disabled mode tests no bounds");equal(0,none.testedBatches,"disabled mode visits no rejection leaves");
                    for(int batch=source.batchCount()-1;batch>=0;batch--)equal(linear.batch(batch),tree.batch(batch),"reverse lookup reuses immutable classified mask");
                }
                throwsType(NullPointerException.class,()->GlNoiseCulling.workingBytes(source,(GlCullingMode)null),"null strategy cannot select an implicit fallback");throwsType(NullPointerException.class,()->new GlNoiseCulling(source,base,1,WorldClip.NONE,(GlCullingMode)null),"null strategy fails before preparation");
            }
        }
        check(cornerChecks>100,"independent corner oracle covers broad source/pose fixtures");
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
        group("shared conservative world face-group decisions",GlMathContracts::worldGroups);
        group("hierarchical world draw bounds and linear agreement",GlMathContracts::worldHierarchicalDraw);
        group("hierarchical diagnostic marker groups and masks",GlMathContracts::markerHierarchicalDraw);
        group("bounded scalar draw preparation and differential groups",GlMathContracts::scalarDrawPreparation);
        group("hierarchical scalar draw rejection and linear agreement",GlMathContracts::scalarHierarchicalDraw);
        System.out.println("PASS "+groups+" context-free display math groups; no OpenGL/native parity is claimed.");
    }
}
