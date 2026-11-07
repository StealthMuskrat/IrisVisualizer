package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.Arrays;
import java.util.Objects;

/** One scalar draw's context-free camera/precision data and conservative original-group decisions. */
final class GlNoiseCulling {
    private static final long MATH_BYTES=1024;
    final float[] matrix,relative;final float scale;final WorldClip.Bounds clip;
    private final byte[] groups;private final int count;private final boolean ranges;
    final int visible,culled,culledFaces,boundsTests,testedBatches;
    static long workingBytes(NoiseSurface surface,boolean ranges) {
        return workingBytes(surface,GlCullingMode.from(ranges));
    }
    static long workingBytes(NoiseSurface surface,GlCullingMode mode) { Objects.requireNonNull(surface);Objects.requireNonNull(mode);return MATH_BYTES+(mode.enabled()?surface.batchCount():0); }
    /** The same grid-only float limits used before CPU/view adoption and GPU upload. */
    static float displayScale(NoiseVolume.Grid grid) {
        Objects.requireNonNull(grid);float value=(float)grid.horizontal().blocksPerSample();
        if(!Float.isFinite(value)||value<Float.MIN_NORMAL||Math.max((double)grid.width()*value,(double)grid.depth()*value)>1048576)throw new GlFailure(GlFailure.Code.LIMIT,"Scalar bins exceed supported float scale/relative-span bounds; data was not reduced");return value;
    }
    /** Original scale, all relative axes and horizontal spacing checks, without arrays or source scans. */
    static void validateView(NoiseSurface surface,GlCamera camera) {
        Objects.requireNonNull(surface);Objects.requireNonNull(camera);if(surface.requiresPlaceholder())throw new GlFailure(GlFailure.Code.STATE,"Complete scalar geometry is required for drawing");
        var grid=surface.volume().grid();float scale=displayScale(grid),x=GlCamera.relativeCoordinate(grid.horizontal().originX(),camera.anchor().x()),z=GlCamera.relativeCoordinate(grid.horizontal().originZ(),camera.anchor().z());GlCamera.relativeCoordinate(grid.vertical().minY(),camera.anchor().y());
        double magnitude=Math.max(Math.abs((double)x)+grid.width()*(double)scale,Math.abs((double)z)+grid.depth()*(double)scale);
        if(scale<4*Math.ulp((float)magnitude))throw new GlFailure(GlFailure.Code.LIMIT,"Scalar sample spacing is too small for this camera anchor's float precision");
    }
    GlNoiseCulling(NoiseSurface surface,GlCamera camera,double aspect,WorldClip scope,boolean ranges) {
        this(surface,camera,aspect,scope,GlCullingMode.from(ranges));
    }
    GlNoiseCulling(NoiseSurface surface,GlCamera camera,double aspect,WorldClip scope,GlCullingMode mode) {
        Objects.requireNonNull(surface);Objects.requireNonNull(camera);Objects.requireNonNull(scope);Objects.requireNonNull(mode);
        validateView(surface,camera);
        var grid=surface.volume().grid();scale=displayScale(grid);
        matrix=camera.matrix(aspect);relative=new float[3];camera.offset(new WorldSnapshot.Position(grid.horizontal().originX(),grid.vertical().minY(),grid.horizontal().originZ()),relative,0);
        clip=scope.relative(camera.anchor());ranges=mode.enabled();count=surface.batchCount();groups=new byte[ranges?count:0];var counts=new Counts();
        if(ranges) {
            var frustum=new GlFrustum(matrix);
            var bounds=scope.enabled()?clip:null;
            if(mode==GlCullingMode.HIERARCHY&&count>0)classify(surface,frustum,bounds,0,count,0,counts);
            else for(int batch=0;batch<count;batch++) {
                counts.boundsTests++;counts.testedBatches++;
                if(frustum.visible(surface,batch,scale,relative,bounds)) { groups[batch]=1;counts.visible++; }else reject(surface,batch,batch+1,counts);
            }
        }else counts.visible=count;
        visible=counts.visible;culled=counts.culled;culledFaces=counts.culledFaces;boundsTests=counts.boundsTests;testedBatches=counts.testedBatches;
    }
    private static final class Counts { int visible,culled,culledFaces,boundsTests,testedBatches; }
    private void reject(NoiseSurface surface,int first,int end,Counts counts) {
        Arrays.fill(groups,first,end,(byte)2);counts.culled+=end-first;counts.culledFaces+=Math.min(surface.size(),end*NoiseSurface.BATCH_FACES)-first*NoiseSurface.BATCH_FACES;
    }
    private void classify(NoiseSurface surface,GlFrustum frustum,WorldClip.Bounds clip,int first,int end,int node,Counts counts) {
        boolean leaf=end-first==1;counts.boundsTests++;if(leaf)counts.testedBatches++;
        var visibility=leaf?(frustum.visible(surface,first,scale,relative,clip)?GlFrustum.Visibility.INTERSECT:GlFrustum.Visibility.OUTSIDE):frustum.classifyHierarchy(surface,node,scale,relative,clip);
        if(visibility==GlFrustum.Visibility.OUTSIDE) { reject(surface,first,end,counts);return; }
        if(leaf||visibility==GlFrustum.Visibility.INSIDE) { Arrays.fill(groups,first,end,(byte)1);counts.visible+=end-first;return; }
        int middle=(first+end)>>>1;classify(surface,frustum,clip,first,middle,node+1,counts);classify(surface,frustum,clip,middle,end,node+middle-first,counts);
    }
    boolean batch(int batch) { Objects.checkIndex(batch,count);return !ranges||groups[batch]==1; }
}
