package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** One draw's context-free section offsets and lazy shared face-group decisions. Never retains GPU objects. */
final class GlWorldCulling {
    // Covers bounded camera/frustum math and clear-vector payload; JVM object headers remain bookkeeping.
    private static final long MATH_BYTES=1024;
    final float[] matrix,offsets;final boolean[] sections;final WorldClip.Bounds clip;
    private final List<WorldMesh> meshes;private final GlFrustum frustum;private final boolean ranges,clipped;private final int[] starts;private final byte[] batches;
    final int empty,visible,culled;private int tests,rejected;
    /** Exact variable primitive payload plus a conservative fixed math allowance; metadata-only sizing. */
    static long workingBytes(WorldScene scene,boolean ranges) {
        Objects.requireNonNull(scene);var usage=scene.usage();long sections=usage.sections();
        return MATH_BYTES+sections*(3L*Float.BYTES+1)+(ranges?(sections+1)*Integer.BYTES+usage.faceBatches():0);
    }
    GlWorldCulling(WorldScene scene,GlCamera camera,double aspect,WorldClip scope,boolean ranges) {
        Objects.requireNonNull(scene);Objects.requireNonNull(camera);Objects.requireNonNull(scope);this.ranges=ranges;clipped=scope.enabled();meshes=scene.sections();
        matrix=camera.matrix(aspect);frustum=new GlFrustum(matrix);clip=scope.relative(camera.anchor());offsets=new float[meshes.size()*3];sections=new boolean[meshes.size()];starts=new int[ranges?meshes.size()+1:0];batches=new byte[ranges?scene.usage().faceBatches():0];
        int empty=0,visible=0,culled=0,batch=0;
        for(int i=0;i<meshes.size();i++) {
            var mesh=meshes.get(i);camera.offset(mesh.origin(),offsets,i*3);
            if(ranges) { starts[i]=batch;batch+=mesh.faceBatchCount(); }
            if(mesh.bounds().isEmpty()) { empty++;continue; }
            sections[i]=frustum.visible(mesh.bounds().orElseThrow(),offsets,i*3)&&(!clipped||clipVisible(mesh.bounds().orElseThrow(),i*3));
            if(sections[i])visible++;else culled++;
        }
        if(ranges) { starts[meshes.size()]=batch;if(batch!=batches.length)throw new GlFailure(GlFailure.Code.STATE,"Prepared world face groups differ from their complete scene receipt"); }
        this.empty=empty;this.visible=visible;this.culled=culled;
    }
    /** One conservative test per actual group across every material/pass, independent of query order. */
    boolean face(int section,int face) {
        Objects.checkIndex(section,meshes.size());var mesh=meshes.get(section);Objects.checkIndex(face,mesh.faces().size());
        if(!sections[section])return false;if(!ranges)return true;
        int batch=face/WorldMesh.BATCH_FACES,index=starts[section]+batch;byte value=batches[index];
        if(value==0) { value=(byte)(frustum.visible(mesh,batch,offsets,section*3,clipped?clip:null)?1:2);batches[index]=value;tests++;if(value==2)rejected++; }
        return value==1;
    }
    int tests() { return tests; }
    int rejected() { return rejected; }
    private boolean clipVisible(BlockMesh.Bounds bounds,int at) {
        float minX=offsets[at]+bounds.min().x(),minY=offsets[at+1]+bounds.min().y(),minZ=offsets[at+2]+bounds.min().z();
        float maxX=offsets[at]+bounds.max().x(),maxY=offsets[at+1]+bounds.max().y(),maxZ=offsets[at+2]+bounds.max().z();
        double ulp=Math.max(Math.ulp(Math.max(Math.abs(minX),Math.abs(maxX))),Math.max(Math.ulp(Math.max(Math.abs(minY),Math.abs(maxY))),Math.ulp(Math.max(Math.abs(minZ),Math.abs(maxZ))))),margin=4*ulp+1e-6;
        return clip.intersects(minX-margin,minY-margin,minZ-margin,maxX+margin,maxY+margin,maxZ+margin);
    }
}
