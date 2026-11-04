package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.BlockMesh;
import dev.irisvisualizer.renderer.NoiseSurface;
import dev.irisvisualizer.renderer.WorldClip;

/** Conservative section AABB rejection using the exact float matrix submitted to the GPU. */
final class GlFrustum {
    private final double[][] planes=new double[6][4];
    GlFrustum(float[] matrix) {
        if(matrix.length!=16)throw new IllegalArgumentException("Expected a 4x4 matrix");
        for(int axis=0;axis<3;axis++)for(int side=0;side<2;side++)for(int component=0;component<4;component++) {
            double value=(double)matrix[component*4+3]+(side==0?1:-1)*(double)matrix[component*4+axis];
            if(!Double.isFinite(value))throw new GlFailure(GlFailure.Code.STATE,"Nonfinite frustum plane");planes[axis*2+side][component]=value;
        }
    }
    boolean visible(BlockMesh.Bounds bounds,float[] offsets,int at) {
        double minX=(double)offsets[at]+bounds.min().x(),minY=(double)offsets[at+1]+bounds.min().y(),minZ=(double)offsets[at+2]+bounds.min().z();
        double maxX=(double)offsets[at]+bounds.max().x(),maxY=(double)offsets[at+1]+bounds.max().y(),maxZ=(double)offsets[at+2]+bounds.max().z();
        return visible(minX,minY,minZ,maxX,maxY,maxZ);
    }
    /** Bounds follow the scalar shader's integer-grid float product and float anchor addition. */
    boolean visible(NoiseSurface surface,int batch,float scale,float[] offset) { return visible(surface,batch,scale,offset,null); }
    /** Null clip retains the original unbounded frustum test. */
    boolean visible(NoiseSurface surface,int batch,float scale,float[] offset,WorldClip.Bounds clip) {
        float minX=offset[0]+surface.batchMinimum(batch,0)*scale,minY=offset[1]+surface.batchMinimum(batch,1),minZ=offset[2]+surface.batchMinimum(batch,2)*scale;
        float maxX=offset[0]+surface.batchMaximum(batch,0)*scale,maxY=offset[1]+surface.batchMaximum(batch,1),maxZ=offset[2]+surface.batchMaximum(batch,2)*scale;
        if(clip!=null) {
            double magnitude=Math.max(Math.max(Math.abs(minX),Math.abs(maxX)),Math.max(Math.max(Math.abs(minY),Math.abs(maxY)),Math.max(Math.abs(minZ),Math.abs(maxZ)))),margin=8*(double)Math.ulp((float)magnitude);
            if(!clip.intersects(minX-margin,minY-margin,minZ-margin,maxX+margin,maxY+margin,maxZ+margin))return false;
        }
        return visible(minX,minY,minZ,maxX,maxY,maxZ);
    }
    private boolean visible(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        for(var p:planes) {
            double x=p[0]>=0?maxX:minX,y=p[1]>=0?maxY:minY,z=p[2]>=0?maxZ:minZ;
            double distance=p[0]*x+p[1]*y+p[2]*z+p[3];
            // Expand, never shrink, the clip volume to tolerate float vertex addition/dot products.
            double margin=1e-5*(Math.abs(p[0])*Math.max(Math.abs(minX),Math.abs(maxX))+Math.abs(p[1])*Math.max(Math.abs(minY),Math.abs(maxY))+Math.abs(p[2])*Math.max(Math.abs(minZ),Math.abs(maxZ))+Math.abs(p[3])+1);
            if(distance< -margin)return false;
        }
        return true;
    }
}
