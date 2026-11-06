package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.Objects;

/** One scalar draw's context-free camera/precision data and conservative original-group decisions. */
final class GlNoiseCulling {
    private static final long MATH_BYTES=1024;
    final float[] matrix,relative;final float scale;final WorldClip.Bounds clip;
    private final byte[] groups;private final int count;private final boolean ranges;
    final int visible,culled,culledFaces;
    static long workingBytes(NoiseSurface surface,boolean ranges) {
        Objects.requireNonNull(surface);return MATH_BYTES+(ranges?surface.batchCount():0);
    }
    /** The same grid-only float limits used before CPU/view adoption and GPU upload. */
    static float displayScale(NoiseVolume.Grid grid) {
        Objects.requireNonNull(grid);float value=(float)grid.horizontal().blocksPerSample();
        if(!Float.isFinite(value)||value<Float.MIN_NORMAL||Math.max((double)grid.width()*value,(double)grid.depth()*value)>1048576)throw new GlFailure(GlFailure.Code.LIMIT,"Scalar bins exceed supported float scale/relative-span bounds; data was not reduced");return value;
    }
    GlNoiseCulling(NoiseSurface surface,GlCamera camera,double aspect,WorldClip scope,boolean ranges) {
        Objects.requireNonNull(surface);Objects.requireNonNull(camera);Objects.requireNonNull(scope);
        if(surface.requiresPlaceholder())throw new GlFailure(GlFailure.Code.STATE,"Complete scalar geometry is required for drawing");
        var grid=surface.volume().grid();scale=displayScale(grid);
        matrix=camera.matrix(aspect);relative=new float[3];camera.offset(new WorldSnapshot.Position(grid.horizontal().originX(),grid.vertical().minY(),grid.horizontal().originZ()),relative,0);
        double magnitude=Math.max(Math.abs((double)relative[0])+grid.width()*(double)scale,Math.abs((double)relative[2])+grid.depth()*(double)scale);
        if(scale<4*Math.ulp((float)magnitude))throw new GlFailure(GlFailure.Code.LIMIT,"Scalar sample spacing is too small for this camera anchor's float precision");
        clip=scope.relative(camera.anchor());this.ranges=ranges;count=surface.batchCount();groups=new byte[ranges?count:0];int visible=0,culled=0,culledFaces=0;
        if(ranges) {
            var frustum=new GlFrustum(matrix);
            for(int batch=0;batch<count;batch++) {
                boolean show=frustum.visible(surface,batch,scale,relative,scope.enabled()?clip:null);groups[batch]=(byte)(show?1:2);
                if(show)visible++;else { culled++;culledFaces+=surface.batchSize(batch); }
            }
        }else visible=count;
        this.visible=visible;this.culled=culled;this.culledFaces=culledFaces;
    }
    boolean batch(int batch) { Objects.checkIndex(batch,count);return !ranges||groups[batch]==1; }
}
