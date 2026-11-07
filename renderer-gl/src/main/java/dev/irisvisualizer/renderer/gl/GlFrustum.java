package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.BlockMesh;
import dev.irisvisualizer.renderer.NoiseSurface;
import dev.irisvisualizer.renderer.WorldClip;
import dev.irisvisualizer.renderer.WorldMesh;

/** Conservative section AABB rejection using the exact float matrix submitted to the GPU. */
final class GlFrustum {
    enum Visibility { OUTSIDE, INTERSECT, INSIDE }
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
    /** Original-order world face bounds, using the geometry shader's float anchor addition. */
    boolean visible(WorldMesh mesh,int batch,float[] offsets,int at,WorldClip.Bounds clip) { return world(mesh,batch,false,offsets,at,clip,false)!=Visibility.OUTSIDE; }
    Visibility classifyHierarchy(WorldMesh mesh,int node,float[] offsets,int at,WorldClip.Bounds clip) { return world(mesh,node,true,offsets,at,clip,true); }
    private Visibility world(WorldMesh mesh,int index,boolean hierarchy,float[] offsets,int at,WorldClip.Bounds clip,boolean classify) {
        float minX=offsets[at]+(hierarchy?mesh.faceBatchHierarchyMinimum(index,0):mesh.faceBatchMinimum(index,0)),minY=offsets[at+1]+(hierarchy?mesh.faceBatchHierarchyMinimum(index,1):mesh.faceBatchMinimum(index,1)),minZ=offsets[at+2]+(hierarchy?mesh.faceBatchHierarchyMinimum(index,2):mesh.faceBatchMinimum(index,2));
        float maxX=offsets[at]+(hierarchy?mesh.faceBatchHierarchyMaximum(index,0):mesh.faceBatchMaximum(index,0)),maxY=offsets[at+1]+(hierarchy?mesh.faceBatchHierarchyMaximum(index,1):mesh.faceBatchMaximum(index,1)),maxZ=offsets[at+2]+(hierarchy?mesh.faceBatchHierarchyMaximum(index,2):mesh.faceBatchMaximum(index,2));
        if(clip!=null) {
            double magnitude=Math.max(Math.max(Math.abs(minX),Math.abs(maxX)),Math.max(Math.max(Math.abs(minY),Math.abs(maxY)),Math.max(Math.abs(minZ),Math.abs(maxZ)))),margin=4*(double)Math.ulp((float)magnitude)+1e-6;
            if(!clip.intersects(minX-margin,minY-margin,minZ-margin,maxX+margin,maxY+margin,maxZ+margin))return Visibility.OUTSIDE;
        }
        return classify(minX,minY,minZ,maxX,maxY,maxZ,clip,classify);
    }
    /** Bounds follow the scalar shader's integer-grid float product and float anchor addition. */
    boolean visible(NoiseSurface surface,int batch,float scale,float[] offset) { return visible(surface,batch,scale,offset,null); }
    /** Null clip retains the original unbounded frustum test. */
    boolean visible(NoiseSurface surface,int batch,float scale,float[] offset,WorldClip.Bounds clip) {
        return scalar(surface,batch,false,scale,offset,clip,false)!=Visibility.OUTSIDE;
    }
    /** Integer parent unions use the identical monotone float transform and conservative leaf policy. */
    Visibility classifyHierarchy(NoiseSurface surface,int node,float scale,float[] offset,WorldClip.Bounds clip) { return scalar(surface,node,true,scale,offset,clip,true); }
    private Visibility scalar(NoiseSurface surface,int index,boolean hierarchy,float scale,float[] offset,WorldClip.Bounds clip,boolean classify) {
        int lowX=hierarchy?surface.batchHierarchyMinimum(index,0):surface.batchMinimum(index,0),lowY=hierarchy?surface.batchHierarchyMinimum(index,1):surface.batchMinimum(index,1),lowZ=hierarchy?surface.batchHierarchyMinimum(index,2):surface.batchMinimum(index,2);
        int highX=hierarchy?surface.batchHierarchyMaximum(index,0):surface.batchMaximum(index,0),highY=hierarchy?surface.batchHierarchyMaximum(index,1):surface.batchMaximum(index,1),highZ=hierarchy?surface.batchHierarchyMaximum(index,2):surface.batchMaximum(index,2);
        float minX=offset[0]+lowX*scale,minY=offset[1]+lowY,minZ=offset[2]+lowZ*scale;
        float maxX=offset[0]+highX*scale,maxY=offset[1]+highY,maxZ=offset[2]+highZ*scale;
        if(clip!=null) {
            double magnitude=Math.max(Math.max(Math.abs(minX),Math.abs(maxX)),Math.max(Math.max(Math.abs(minY),Math.abs(maxY)),Math.max(Math.abs(minZ),Math.abs(maxZ)))),margin=8*(double)Math.ulp((float)magnitude);
            if(!clip.intersects(minX-margin,minY-margin,minZ-margin,maxX+margin,maxY+margin,maxZ+margin))return Visibility.OUTSIDE;
        }
        return classify(minX,minY,minZ,maxX,maxY,maxZ,clip,classify);
    }
    private Visibility classify(float minX,float minY,float minZ,float maxX,float maxY,float maxZ,WorldClip.Bounds clip,boolean classify) {
        if(!visible(minX,minY,minZ,maxX,maxY,maxZ))return Visibility.OUTSIDE;
        if(!classify||clip!=null&&(!clip.contains(minX,minY,minZ)||!clip.contains(maxX,maxY,maxZ)))return Visibility.INTERSECT;
        // Strict unexpanded containment is sufficient for every enclosed leaf's conservative test.
        for(var p:planes) {
            double x=p[0]>=0?minX:maxX,y=p[1]>=0?minY:maxY,z=p[2]>=0?minZ:maxZ;
            if(p[0]*x+p[1]*y+p[2]*z+p[3]<0)return Visibility.INTERSECT;
        }
        return Visibility.INSIDE;
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
