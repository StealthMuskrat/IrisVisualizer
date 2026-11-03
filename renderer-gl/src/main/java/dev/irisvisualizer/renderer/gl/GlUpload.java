package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Context-affine resumable upload. Paused CPU input and all GPU reservations remain device-owned. */
public final class GlUpload implements AutoCloseable {
    public enum Phase { VALIDATING, RESERVING, TEXTURE_STORAGE, TEXTURE_DATA, SECTION, VERTEX_STORAGE, VERTEX_DATA,
        INDEX_STORAGE, INDEX_DATA, GEOMETRY_LAYOUT, MARKER_STORAGE, MARKER_DATA, MARKER_LAYOUT, SECTION_DONE, FINISHING,
        READY, TAKEN, CANCELLED, FAILED, CLOSED }
    public record Progress(Phase phase,int validatedSections,int totalSections,int validatedFaces,int totalFaces,int completedSections,long uploadedBytes,long totalBytes) {}
    public record Step(Progress progress,int transferBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds) {}
    private static final int CHUNK_BYTES=65536;
    private final GlRenderer renderer;private final GlDevice device;private final WorldSnapshot.Source source;
    private final int totalSections,totalFaces;private final long totalBytes;
    private WorldScene scene;private GlDevice.Allocation allocation;private GlScene completed;private final List<GlScene.Section> sections;
    private Phase phase=Phase.VALIDATING;private int validatedSection,validatedFace,validationFace=-1,validatedPage,geometryCount,markerSections;
    private long markerBytes,uploadedBytes;private int page,pageRow,section,bufferSlot,vaoSlot,geometryVao,markerVao,vertexBuffer,indexBuffer,markerBuffer,floatOffset,indexOffset,markerOffset;
    private GlUpload(GlRenderer renderer,WorldScene scene,GlDevice.Allocation allocation) {
        this.renderer=renderer;device=renderer.device;this.scene=scene;this.allocation=allocation;source=scene.snapshot().source();
        totalSections=scene.sections().size();totalFaces=scene.usage().faces();totalBytes=scene.usage().meshBytes()+(long)scene.usage().markers()*12+scene.raster().rgbaBytes();sections=new ArrayList<>(totalSections);
    }
    /** Fresh nonnull leases transfer on every outcome; re-transferring a device-owned lease is rejected intact. */
    public static GlUpload begin(GlRenderer renderer,WorldScene.Result owner) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(owner);var device=renderer.device;GlDevice.Allocation allocation=null;boolean alreadyOwned=false;
        try {
            alreadyOwned=device.ownsCpu(owner);if(alreadyOwned)throw new GlFailure(GlFailure.Code.STATE,"This CPU result lease was already transferred to the device");
            device.live(renderer.allocation);var scene=owner.scene().orElseThrow(()->new GlFailure(GlFailure.Code.STATE,"A complete CPU scene is required"));
            allocation=device.reserve(0,scene.usage().knownPayloadBytes(),0,0,0,0,0,0,0,owner);device.depend(allocation,renderer.allocation);return new GlUpload(renderer,scene,allocation);
        }catch(RuntimeException|Error failure) {
            if(allocation==null) { if(!alreadyOwned)owner.close(); }else try { device.retire(allocation); }catch(RuntimeException retirement) { failure.addSuppressed(retirement); }throw failure;
        }
    }
    public WorldSnapshot.Source source() { return source; }
    public Progress progress() { return new Progress(phase,validatedSection,totalSections,validatedFace,totalFaces,section,uploadedBytes,totalBytes); }
    public boolean ready() { return phase==Phase.READY; }
    boolean ownsResources() { return allocation!=null; }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Incremental GPU upload cancelled"); }
    private boolean gpuPhase() {
        return switch(phase) { case TEXTURE_STORAGE,TEXTURE_DATA,VERTEX_STORAGE,VERTEX_DATA,INDEX_STORAGE,INDEX_DATA,GEOMETRY_LAYOUT,MARKER_STORAGE,MARKER_DATA,MARKER_LAYOUT->true;default->false; };
    }
    public Step step(GlUploadBudget budget,BooleanSupplier cancelled) {
        Objects.requireNonNull(budget);Objects.requireNonNull(cancelled);device.current();
        if(phase==Phase.READY)return new Step(progress(),0,0,0,0,0);
        if(allocation==null||phase==Phase.FAILED||phase==Phase.CANCELLED)throw new GlFailure(GlFailure.Code.STATE,"Upload is no longer resumable");device.live(allocation);
        try { return advance(budget,cancelled); }
        catch(RuntimeException|Error failure) {
            try { dispose(failure instanceof CancellationException?Phase.CANCELLED:Phase.FAILED); }catch(RuntimeException retirement) { failure.addSuppressed(retirement); }throw failure;
        }
    }
    private Step advance(GlUploadBudget budget,BooleanSupplier cancelled) {
        long start=System.nanoTime(),storage=0;int bytes=0,actions=0,work=0;boolean finish=false;GlState state=null;
        try {
            device.live(renderer.allocation);check(cancelled);
            units:while(work<budget.workUnits()&&(work==0||System.nanoTime()-start<budget.nanoseconds())) {
                check(cancelled);boolean gpu=gpuPhase();if(gpu&&actions>=budget.actions())break;
                if(gpu&&state==null) { state=new GlState(device);GlState.unpack();glActiveTexture(GL_TEXTURE0);glBindSampler(0,0); }
                int transferred=0;
                switch(phase) {
                    case VALIDATING -> validateUnit();
                    case RESERVING -> {
                        if(validatedFace!=totalFaces||markerBytes!=(long)scene.usage().markers()*12)throw new GlFailure(GlFailure.Code.STATE,"Upload validation counts disagree with the CPU scene");
                        device.expand(allocation,totalBytes,geometryCount*2+markerSections,scene.raster().pages().size(),geometryCount+markerSections);phase=scene.raster().pages().isEmpty()?Phase.SECTION:Phase.TEXTURE_STORAGE;
                    }
                    case TEXTURE_STORAGE -> {
                        var image=scene.raster().pages().get(page);allocation.textures[page]=glGenTextures();glBindTexture(GL_TEXTURE_2D,allocation.textures[page]);
                        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_BASE_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);
                        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,image.width(),image.height(),0,GL_RGBA,GL_UNSIGNED_BYTE,0L);device.check("incremental texture storage");storage+=image.bytes();pageRow=0;phase=Phase.TEXTURE_DATA;
                    }
                    case TEXTURE_DATA -> {
                        var image=scene.raster().pages().get(page);int rowBytes=image.width()*4,capacity=Math.min(CHUNK_BYTES,Math.min(device.limits().stagingBytes(),budget.transferBytes()-bytes)),rows=Math.min(image.height()-pageRow,capacity/rowBytes);
                        if(rows==0)break units;transferred=rows*rowBytes;var stage=device.staging();stage.limit(transferred);stage.put(image.pixels().slice(pageRow*rowBytes,transferred));stage.flip();glBindTexture(GL_TEXTURE_2D,allocation.textures[page]);glTexSubImage2D(GL_TEXTURE_2D,0,0,pageRow,image.width(),rows,GL_RGBA,GL_UNSIGNED_BYTE,stage);device.check("incremental texture rows");pageRow+=rows;
                        if(pageRow==image.height()) { page++;phase=page<scene.raster().pages().size()?Phase.TEXTURE_STORAGE:Phase.SECTION; }
                    }
                    case SECTION -> {
                        if(section==totalSections) { phase=Phase.FINISHING;break; }
                        geometryVao=0;markerVao=0;var mesh=scene.sections().get(section);phase=mesh.indexCount()>0?Phase.VERTEX_STORAGE:mesh.markers().isEmpty()?Phase.SECTION_DONE:Phase.MARKER_STORAGE;
                    }
                    case VERTEX_STORAGE -> {
                        var mesh=scene.sections().get(section);allocation.vaos[vaoSlot++]=geometryVao=glGenVertexArrays();glBindVertexArray(geometryVao);allocation.buffers[bufferSlot++]=vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);long size=(long)mesh.vertexCount()*32;glBufferData(GL_ARRAY_BUFFER,size,GL_STATIC_DRAW);device.check("incremental vertex storage");storage+=size;floatOffset=0;phase=Phase.VERTEX_DATA;
                    }
                    case VERTEX_DATA -> {
                        var source=scene.sections().get(section).vertexBuffer();int count=Math.min(source.remaining()-floatOffset,chunk(budget.transferBytes()-bytes)/4);if(count==0)break units;
                        var stage=device.staging();stage.limit(count*4);var values=stage.asFloatBuffer();for(int i=0;i<count;i++) { if((i&1023)==0)check(cancelled);values.put(source.get(floatOffset+i)); }
                        glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferSubData(GL_ARRAY_BUFFER,(long)floatOffset*4,stage);device.check("incremental vertex data");floatOffset+=count;transferred=count*4;if(floatOffset==source.remaining())phase=Phase.INDEX_STORAGE;
                    }
                    case INDEX_STORAGE -> {
                        var mesh=scene.sections().get(section);glBindVertexArray(geometryVao);allocation.buffers[bufferSlot++]=indexBuffer=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indexBuffer);long size=(long)mesh.indexCount()*4;glBufferData(GL_ELEMENT_ARRAY_BUFFER,size,GL_STATIC_DRAW);device.check("incremental index storage");storage+=size;indexOffset=0;phase=Phase.INDEX_DATA;
                    }
                    case INDEX_DATA -> {
                        var source=scene.sections().get(section).indexBuffer();int count=Math.min(source.remaining()-indexOffset,chunk(budget.transferBytes()-bytes)/4);if(count==0)break units;
                        var stage=device.staging();stage.limit(count*4);var values=stage.asIntBuffer();for(int i=0;i<count;i++) { if((i&1023)==0)check(cancelled);values.put(source.get(indexOffset+i)); }
                        glBindBuffer(GL_ARRAY_BUFFER,indexBuffer);glBufferSubData(GL_ARRAY_BUFFER,(long)indexOffset*4,stage);device.check("incremental index data");indexOffset+=count;transferred=count*4;if(indexOffset==source.remaining())phase=Phase.GEOMETRY_LAYOUT;
                    }
                    case GEOMETRY_LAYOUT -> {
                        glBindVertexArray(geometryVao);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glVertexAttribPointer(0,3,GL_FLOAT,false,32,0L);glEnableVertexAttribArray(0);glVertexAttribPointer(1,3,GL_FLOAT,false,32,12L);glEnableVertexAttribArray(1);glVertexAttribPointer(2,2,GL_FLOAT,false,32,24L);glEnableVertexAttribArray(2);device.check("incremental geometry layout");phase=scene.sections().get(section).markers().isEmpty()?Phase.SECTION_DONE:Phase.MARKER_STORAGE;
                    }
                    case MARKER_STORAGE -> {
                        var mesh=scene.sections().get(section);allocation.vaos[vaoSlot++]=markerVao=glGenVertexArrays();glBindVertexArray(markerVao);glBindBuffer(GL_ARRAY_BUFFER,renderer.allocation.buffers[0]);glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,renderer.allocation.buffers[1]);allocation.buffers[bufferSlot++]=markerBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,markerBuffer);long size=(long)mesh.markers().size()*12;glBufferData(GL_ARRAY_BUFFER,size,GL_STATIC_DRAW);device.check("incremental marker storage");storage+=size;markerOffset=0;phase=Phase.MARKER_DATA;
                    }
                    case MARKER_DATA -> {
                        var mesh=scene.sections().get(section);int count=Math.min(mesh.markers().size()-markerOffset,chunk(budget.transferBytes()-bytes)/12);if(count==0)break units;
                        var stage=device.staging();stage.limit(count*12);var values=stage.asFloatBuffer();var origin=mesh.origin();
                        for(int i=0;i<count;i++) { if((i&1023)==0)check(cancelled);var p=mesh.markers().get(markerOffset+i).position();values.put((float)(p.x()-origin.x())).put((float)((long)p.y()-origin.y())).put((float)(p.z()-origin.z())); }
                        glBindBuffer(GL_ARRAY_BUFFER,markerBuffer);glBufferSubData(GL_ARRAY_BUFFER,(long)markerOffset*12,stage);device.check("incremental marker data");markerOffset+=count;transferred=count*12;if(markerOffset==mesh.markers().size())phase=Phase.MARKER_LAYOUT;
                    }
                    case MARKER_LAYOUT -> { glBindVertexArray(markerVao);glBindBuffer(GL_ARRAY_BUFFER,markerBuffer);glVertexAttribPointer(3,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(3);glVertexAttribDivisor(3,1);device.check("incremental marker layout");phase=Phase.SECTION_DONE; }
                    case SECTION_DONE -> { var mesh=scene.sections().get(section);sections.add(new GlScene.Section(mesh.origin(),mesh.ranges(),geometryVao,markerVao,mesh.markers().size()));section++;phase=Phase.SECTION; }
                    case FINISHING -> { if(uploadedBytes!=totalBytes||bufferSlot!=allocation.buffers.length||vaoSlot!=allocation.vaos.length||sections.size()!=totalSections)throw new GlFailure(GlFailure.Code.STATE,"GPU upload did not cover the complete admitted scene");device.check("complete incremental upload");finish=true; }
                    default -> throw new GlFailure(GlFailure.Code.STATE,"Unexpected upload phase");
                }
                bytes+=transferred;uploadedBytes+=transferred;work++;if(gpu)actions++;if(finish)break;
            }
        }finally { if(state!=null)state.close(); }
        if(finish) { check(cancelled);completed=new GlScene(device,allocation,sections);scene=null;sections.clear();phase=Phase.READY; }
        return result(start,bytes,storage,actions,work);
    }
    private int chunk(int remaining) { return Math.min(CHUNK_BYTES,Math.min(device.limits().stagingBytes(),remaining)); }
    private Step result(long start,int bytes,long storage,int actions,int work) { return new Step(progress(),bytes,storage,actions,work,System.nanoTime()-start); }
    private void validateUnit() {
        if(validatedSection<totalSections) {
            var mesh=scene.sections().get(validatedSection);
            if(validationFace<0) { mesh.requireRaster(scene.raster());if(mesh.indexCount()>0)geometryCount++;if(!mesh.markers().isEmpty())markerSections++;markerBytes+=(long)mesh.markers().size()*12;validationFace=0; }
            else if(validationFace<mesh.faces().size()) { var face=mesh.faces().get(validationFace++);validatedFace++;if(face.source().alpha()==TexturePreparation.AlphaClass.TRANSLUCENT||face.source().source().tintIndex()>=0)throw new GlFailure(GlFailure.Code.MATERIAL,"Native tint and translucent rendering are required for this scene"); }
            else { validatedSection++;validationFace=-1; }
        } else if(validatedPage<scene.raster().pages().size()) {
            var image=scene.raster().pages().get(validatedPage++);if(image.width()>device.maximumTextureSize()||image.height()>device.maximumTextureSize()||image.width()*4>device.limits().stagingBytes())throw new GlFailure(GlFailure.Code.CAPABILITY,"Atlas exceeds hardware or bounded row-staging dimensions");
        } else phase=Phase.RESERVING;
    }
    public GlScene take() {
        device.current();if(phase!=Phase.READY||completed==null)throw new GlFailure(GlFailure.Code.STATE,"No complete GPU upload is available");var result=completed;completed=null;allocation=null;phase=Phase.TAKEN;return result;
    }
    private void dispose(Phase terminal) {
        try { if(allocation!=null)device.retire(allocation); }
        finally { if(allocation==null||allocation.retired||allocation.released) { allocation=null;scene=null;completed=null;sections.clear(); }phase=terminal; }
    }
    @Override public void close() { if(phase==Phase.TAKEN||phase==Phase.CLOSED)return;dispose(Phase.CLOSED); }
}
