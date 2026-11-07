package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** One draw's context-free section offsets and shared original face-group decisions. Never retains GPU objects. */
final class GlWorldCulling {
    // Covers bounded camera/frustum math and clear-vector payload; JVM object headers remain bookkeeping.
    private static final long MATH_BYTES=1024;
    final float[] matrix,offsets;final boolean[] sections;final WorldClip.Bounds clip;
    private final List<WorldMesh> meshes;private final GlFrustum frustum;private final boolean ranges,clipped;private final int[] starts,markerStarts;private final byte[] batches,markerBatches;
    final int empty,visible,culled;private int tests,rejected,boundsTests,markerTests,markerRejected,markerBoundsTests;
    /** Exact variable primitive payload plus a conservative fixed math allowance; metadata-only sizing. */
    static long workingBytes(WorldScene scene,boolean ranges) {
        return workingBytes(scene,GlCullingMode.from(ranges));
    }
    static long workingBytes(WorldScene scene,GlCullingMode mode) {
        Objects.requireNonNull(scene);Objects.requireNonNull(mode);boolean ranges=mode.enabled();var usage=scene.usage();long sections=usage.sections();
        return MATH_BYTES+sections*(3L*Float.BYTES+1)+(ranges?(sections+1)*Integer.BYTES+usage.faceBatches()+(usage.markerBatches()>0?(sections+1)*Integer.BYTES+usage.markerBatches():0):0);
    }
    GlWorldCulling(WorldScene scene,GlCamera camera,double aspect,WorldClip scope,boolean ranges) {
        this(scene,camera,aspect,scope,GlCullingMode.from(ranges));
    }
    GlWorldCulling(WorldScene scene,GlCamera camera,double aspect,WorldClip scope,GlCullingMode mode) {
        Objects.requireNonNull(scene);Objects.requireNonNull(camera);Objects.requireNonNull(scope);Objects.requireNonNull(mode);camera.requireSceneRange(scene);boolean ranges=mode.enabled();this.ranges=ranges;clipped=scope.enabled();meshes=scene.sections();
        matrix=camera.matrix(aspect);frustum=new GlFrustum(matrix);clip=scope.relative(camera.anchor());offsets=new float[meshes.size()*3];sections=new boolean[meshes.size()];starts=new int[ranges?meshes.size()+1:0];batches=new byte[ranges?scene.usage().faceBatches():0];
        boolean markerRanges=ranges&&scene.usage().markerBatches()>0;markerStarts=new int[markerRanges?meshes.size()+1:0];markerBatches=new byte[markerRanges?scene.usage().markerBatches():0];
        int empty=0,visible=0,culled=0,batch=0,markerBatch=0;
        for(int i=0;i<meshes.size();i++) {
            var mesh=meshes.get(i);camera.offset(mesh.origin(),offsets,i*3);
            if(ranges) { starts[i]=batch;batch+=mesh.faceBatchCount(); }
            if(markerRanges) { markerStarts[i]=markerBatch;markerBatch+=mesh.markerBatchCount(); }
            if(mesh.bounds().isEmpty()) { empty++;continue; }
            sections[i]=frustum.visible(mesh.bounds().orElseThrow(),offsets,i*3)&&(!clipped||clipVisible(mesh.bounds().orElseThrow(),i*3));
            if(sections[i])visible++;else culled++;
        }
        if(ranges) { starts[meshes.size()]=batch;if(batch!=batches.length)throw new GlFailure(GlFailure.Code.STATE,"Prepared world face groups differ from their complete scene receipt"); }
        if(markerRanges) { markerStarts[meshes.size()]=markerBatch;if(markerBatch!=markerBatches.length)throw new GlFailure(GlFailure.Code.STATE,"Prepared marker groups differ from complete source receipt"); }
        this.empty=empty;this.visible=visible;this.culled=culled;
        if(mode==GlCullingMode.HIERARCHY)for(int section=0;section<meshes.size();section++) {
            var mesh=meshes.get(section);if(sections[section]) { if(mesh.faceBatchCount()>0)classify(mesh,section,0,mesh.faceBatchCount(),0,false);if(mesh.markerBatchCount()>0)classify(mesh,section,0,mesh.markerBatchCount(),0,true); }
        }
    }
    /** Read a prepared hierarchy mask, or lazily cache each independent leaf in LINEAR mode. */
    boolean face(int section,int face) {
        Objects.checkIndex(section,meshes.size());var mesh=meshes.get(section);Objects.checkIndex(face,mesh.faces().size());
        if(!sections[section])return false;if(!ranges)return true;
        int batch=face/WorldMesh.BATCH_FACES,index=starts[section]+batch;byte value=batches[index];
        if(value==0) { value=(byte)(frustum.visible(mesh,batch,offsets,section*3,clipped?clip:null)?1:2);batches[index]=value;tests++;boundsTests++;if(value==2)rejected++; }
        return value==1;
    }
    boolean marker(int section,int marker) {
        Objects.checkIndex(section,meshes.size());var mesh=meshes.get(section);Objects.checkIndex(marker,mesh.markers().size());
        if(!sections[section])return false;if(!ranges)return true;
        int batch=marker/WorldMesh.BATCH_FACES,index=markerStarts[section]+batch;byte value=markerBatches[index];
        if(value==0) { value=(byte)(frustum.markerVisible(mesh,batch,offsets,section*3,clipped?clip:null)?1:2);markerBatches[index]=value;markerTests++;markerBoundsTests++;if(value==2)markerRejected++; }
        return value==1;
    }
    private void classify(WorldMesh mesh,int section,int first,int end,int node,boolean markers) {
        boolean leaf=end-first==1;if(markers) { markerBoundsTests++;if(leaf)markerTests++; }else { boundsTests++;if(leaf)tests++; }
        var scope=clipped?clip:null;GlFrustum.Visibility visibility;
        if(leaf)visibility=(markers?frustum.markerVisible(mesh,first,offsets,section*3,scope):frustum.visible(mesh,first,offsets,section*3,scope))?GlFrustum.Visibility.INTERSECT:GlFrustum.Visibility.OUTSIDE;
        else visibility=markers?frustum.classifyMarkerHierarchy(mesh,node,offsets,section*3,scope):frustum.classifyHierarchy(mesh,node,offsets,section*3,scope);
        byte[] decisions=markers?markerBatches:batches;int start=(markers?markerStarts:starts)[section];
        if(visibility==GlFrustum.Visibility.OUTSIDE) { Arrays.fill(decisions,start+first,start+end,(byte)2);if(markers)markerRejected+=end-first;else rejected+=end-first;return; }
        if(leaf||visibility==GlFrustum.Visibility.INSIDE) { Arrays.fill(decisions,start+first,start+end,(byte)1);return; }
        int middle=(first+end)>>>1;classify(mesh,section,first,middle,node+1,markers);classify(mesh,section,middle,end,node+middle-first,markers);
    }
    int markerTests() { return markerTests; }
    int markerRejected() { return markerRejected; }
    int markerBoundsTests() { return markerBoundsTests; }
    int boundsTests() { return boundsTests; }
    int tests() { return tests; }
    int rejected() { return rejected; }
    private boolean clipVisible(BlockMesh.Bounds bounds,int at) {
        float minX=offsets[at]+bounds.min().x(),minY=offsets[at+1]+bounds.min().y(),minZ=offsets[at+2]+bounds.min().z();
        float maxX=offsets[at]+bounds.max().x(),maxY=offsets[at+1]+bounds.max().y(),maxZ=offsets[at+2]+bounds.max().z();
        double ulp=Math.max(Math.ulp(Math.max(Math.abs(minX),Math.abs(maxX))),Math.max(Math.ulp(Math.max(Math.abs(minY),Math.abs(maxY))),Math.ulp(Math.max(Math.abs(minZ),Math.abs(maxZ))))),margin=4*ulp+1e-6;
        return clip.intersects(minX-margin,minY-margin,minZ-margin,maxX+margin,maxY+margin,maxZ+margin);
    }
}
