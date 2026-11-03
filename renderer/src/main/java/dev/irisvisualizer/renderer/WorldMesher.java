package dev.irisvisualizer.renderer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Two-pass bounded assembly; no generated state rewriting, native choice RNG, or opacity inference. */
public final class WorldMesher {
    private WorldMesher() {}
    public record Limits(int axis,int cells,int faces,int markers,long meshBytes,long prototypeBytes,long sourceIndexBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(32,32768,1048576,32768,64L*1024*1024,64L*1024*1024,256L*1024*1024,512L*1024*1024);
        public Limits { if(axis<1||axis>32||cells<1||cells>32768||faces<1||faces>1048576||markers<0||markers>32768||meshBytes<0||meshBytes>256L*1024*1024||prototypeBytes<0||prototypeBytes>64L*1024*1024||sourceIndexBytes<0||sourceIndexBytes>256L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("World section limits exceed renderer bounds"); }
    }
    private record Buffers(FloatBuffer vertices,IntBuffer indices) { Buffers(AtlasMesh mesh) { this(mesh.vertexBuffer(),mesh.indexBuffer()); } }
    private static final class Work {
        final WorldModels models;final WorldSnapshot world;final WorldVisibility visibility;final WorldSnapshot.Extent extent;final Limits limits;final BooleanSupplier cancelled;
        final IdentityHashMap<AtlasMesh,Buffers> buffers=new IdentityHashMap<>();
        int faceCount,markerCount,cellCount;float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        Work(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,Limits limits,BooleanSupplier cancelled) { this.models=models;world=models.snapshot();this.visibility=visibility;this.extent=extent;this.limits=limits;this.cancelled=cancelled; }
        int sourceCell(int x,int y,int z) {
            var source=world.extent();long dy=(long)extent.minY()-source.minY()+y,dz=extent.minZ()-source.minZ()+z,dx=extent.minX()-source.minX()+x;
            return (int)((dy*source.depth()+dz)*source.width()+dx);
        }
        boolean hidden(int cell,AtlasMesh.Face face) { var direction=face.source().cullface();return direction.isPresent()&&visibility.hidden(cell,direction.get()); }
        void bounds(float x,float y,float z,int cell) {
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||Math.abs(x)>128||Math.abs(y)>128||Math.abs(z)>128)throw WorldProblem.at(WorldProblem.Code.COORDINATE,world.extent().position(cell),"Section-relative vertex is outside the supported finite precision range");
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        Optional<BlockMesh.Bounds> bounds() { return minX==Float.POSITIVE_INFINITY?Optional.empty():Optional.of(new BlockMesh.Bounds(new ModelPlan.Vec3(minX,minY,minZ),new ModelPlan.Vec3(maxX,maxY,maxZ))); }
        void count() {
            for(int y=0;y<extent.height();y++)for(int z=0;z<extent.depth();z++)for(int x=0;x<extent.width();x++) {
                WorldProblem.check(cancelled);int cell=sourceCell(x,y,z);var entry=models.entry(cell);if(entry.kind()==WorldModels.Kind.EMPTY)continue;
                if(entry.kind()==WorldModels.Kind.PLACEHOLDER) {
                    cellCount++;if(++markerCount>limits.markers())throw WorldProblem.at(WorldProblem.Code.LIMIT,world.extent().position(cell),"Visual placeholder marker budget reached");continue;
                }
                var mesh=entry.mesh().orElseThrow();int before=faceCount;
                for(var face:mesh.faces()) {
                    WorldProblem.check(cancelled);if(hidden(cell,face))continue;
                    if(++faceCount>limits.faces()||(long)faceCount*MeshPacket.PACKED_BYTES_PER_QUAD>limits.meshBytes())throw WorldProblem.at(WorldProblem.Code.LIMIT,world.extent().position(cell),"World section face or packed array budget reached");
                }
                if(faceCount>before)cellCount++;
            }
        }
        WorldMesh build(WorldMesh.Usage usage) {
            float[] vertices=new float[faceCount*4*MeshPacket.STRIDE_FLOATS];int[] indices=new int[faceCount*6];
            var cells=new ArrayList<WorldMesh.Cell>(cellCount);var faces=new ArrayList<WorldMesh.Face>(faceCount);var markers=new ArrayList<WorldMesh.Marker>(markerCount);var ranges=new ArrayList<AtlasMesh.DrawRange>();
            int rangeStart=0,rangePage=-1;TexturePreparation.AlphaClass rangeAlpha=null;
            for(int y=0;y<extent.height();y++)for(int z=0;z<extent.depth();z++)for(int x=0;x<extent.width();x++) {
                WorldProblem.check(cancelled);int cell=sourceCell(x,y,z),entryIndex=models.entryIndex(cell);var entry=models.entries().get(entryIndex);if(entry.kind()==WorldModels.Kind.EMPTY)continue;
                int cellRecord=cells.size(),first=faces.size();
                if(entry.kind()==WorldModels.Kind.PLACEHOLDER) {
                    cells.add(new WorldMesh.Cell(cell,entryIndex,first,0,markers.size()));markers.add(new WorldMesh.Marker(cellRecord,world.extent().position(cell),entry.state(),entry.problems()));bounds(x,y,z,cell);bounds(x+1,y+1,z+1,cell);continue;
                }
                var mesh=entry.mesh().orElseThrow();var source=buffers.computeIfAbsent(mesh,Buffers::new);
                double offsetX=x+models.offsets().component(cell,0),offsetY=y+models.offsets().component(cell,1),offsetZ=z+models.offsets().component(cell,2);
                for(int f=0;f<mesh.faces().size();f++) {
                    WorldProblem.check(cancelled);var face=mesh.faces().get(f);if(hidden(cell,face))continue;int destination=faces.size(),sourceBase=f*4*MeshPacket.STRIDE_FLOATS,destinationBase=destination*4*MeshPacket.STRIDE_FLOATS;
                    for(int vertex=0;vertex<4;vertex++) {
                        int from=sourceBase+vertex*MeshPacket.STRIDE_FLOATS,to=destinationBase+vertex*MeshPacket.STRIDE_FLOATS;
                        float px=(float)(offsetX+source.vertices().get(from)),py=(float)(offsetY+source.vertices().get(from+1)),pz=(float)(offsetZ+source.vertices().get(from+2));bounds(px,py,pz,cell);
                        vertices[to]=px;vertices[to+1]=py;vertices[to+2]=pz;for(int component=3;component<8;component++)vertices[to+component]=source.vertices().get(from+component);
                    }
                    for(int i=0;i<6;i++) { int corner=source.indices().get(f*6+i)-f*4;if(corner<0||corner>=4)throw WorldProblem.at(WorldProblem.Code.ASSIGNMENT,world.extent().position(cell),"Prototype indices cross their source quad");indices[destination*6+i]=destination*4+corner; }
                    faces.add(new WorldMesh.Face(cellRecord,f,face));
                    if(rangePage!=face.page()||rangeAlpha!=face.alpha()) {
                        int start=destination*6;if(rangePage>=0)ranges.add(new AtlasMesh.DrawRange(rangeStart,start-rangeStart,rangePage,rangeAlpha));rangeStart=start;rangePage=face.page();rangeAlpha=face.alpha();
                    }
                }
                if(faces.size()>first)cells.add(new WorldMesh.Cell(cell,entryIndex,first,faces.size()-first,-1));
            }
            if(rangePage>=0)ranges.add(new AtlasMesh.DrawRange(rangeStart,indices.length-rangeStart,rangePage,rangeAlpha));
            if(faces.size()!=faceCount||markers.size()!=markerCount||cells.size()!=cellCount)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Section count and emit passes disagree");
            WorldProblem.check(cancelled);return WorldMesh.success(models,visibility,extent,vertices,indices,cells,faces,markers,ranges,bounds(),usage);
        }
    }
    public static WorldMesh assemble(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,BooleanSupplier cancelled) { return assemble(models,visibility,extent,Limits.DEFAULT,cancelled); }
    public static WorldMesh assemble(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,Limits limits,BooleanSupplier cancelled) {
        return assemble(plan(models,visibility,extent,limits,cancelled),cancelled);
    }
    public static WorldMeshPlan plan(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(models);Objects.requireNonNull(visibility);Objects.requireNonNull(extent);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return count(models,visibility,extent,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return WorldMeshPlan.placeholder(models,visibility,extent,limits,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return WorldMeshPlan.placeholder(models,visibility,extent,limits,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to plan the world section; no partial plan was published"))); }
    }
    public static WorldMesh assemble(WorldMeshPlan plan,BooleanSupplier cancelled) {
        Objects.requireNonNull(plan);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try {
            if(plan.requiresPlaceholder())return WorldMesh.placeholder(plan.models(),plan.visibility(),plan.extent(),plan.problems());
            return emit(plan,cancelled);
        } catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return WorldMesh.placeholder(plan.models(),plan.visibility(),plan.extent(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return WorldMesh.placeholder(plan.models(),plan.visibility(),plan.extent(),List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to assemble the world section; no partial mesh was published"))); }
    }
    private static WorldMesh emit(WorldMeshPlan plan,BooleanSupplier cancelled) {
        var work=new Work(plan.models(),plan.visibility(),plan.extent(),plan.limits(),cancelled);var usage=plan.usage().orElseThrow();
        work.faceCount=usage.faces();work.markerCount=usage.markers();work.cellCount=usage.nonEmptyCells();return work.build(usage);
    }
    private static WorldMeshPlan count(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,Limits limits,BooleanSupplier cancelled) {
        if(models.requiresPlaceholder())return WorldMeshPlan.placeholder(models,visibility,extent,limits,models.problems());
        if(visibility.requiresPlaceholder())return WorldMeshPlan.placeholder(models,visibility,extent,limits,visibility.problems());
        if(visibility.snapshot()!=models.snapshot()||visibility.layout()!=models.layout())throw WorldProblem.fail(WorldProblem.Code.VISIBILITY,"World models and native visibility must share the exact snapshot and atlas epoch");
        if(!models.snapshot().extent().contains(extent))throw WorldProblem.fail(WorldProblem.Code.EXTENT,"Requested world section lies outside the captured native volume");
        if(extent.width()>limits.axis()||extent.height()>limits.axis()||extent.depth()>limits.axis()||extent.cells()>limits.cells())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"World section dimensions exceed the assembly budget; request explicit sections without truncating the world");
        long source=models.snapshot().indexBytes()+models.indexBytes()+models.offsets().bytes(),mask=visibility.bytes();
        if(source+mask>limits.sourceIndexBytes()||models.meshBytes()>limits.prototypeBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Retained native indices, visibility or prototype mesh budget reached");
        long atlas=models.layout().usage().orElseThrow().knownPayloadBytes()-models.layout().usage().orElseThrow().boundMeshBytes();
        if(source+mask+models.meshBytes()+atlas>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Combined world, prototype and atlas payload budget reached");
        var work=new Work(models,visibility,extent,limits,cancelled);work.count();
        var usage=new WorldMesh.Usage(source,mask,models.meshBytes(),atlas,(long)work.faceCount*MeshPacket.PACKED_BYTES_PER_QUAD,(int)extent.cells(),work.cellCount,work.faceCount,work.markerCount);
        if(usage.knownPayloadBytes()>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Combined world section output payload budget reached");
        WorldProblem.check(cancelled);return WorldMeshPlan.success(models,visibility,extent,limits,usage);
    }
}
