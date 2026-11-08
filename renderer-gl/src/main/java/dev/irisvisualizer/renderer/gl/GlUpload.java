package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Context-affine resumable upload. Paused CPU input and all GPU reservations remain device-owned. */
public final class GlUpload implements AutoCloseable {
    public enum Phase { VALIDATING, RESERVING, READ_FRAMEBUFFER, TEXTURE_STORAGE, TEXTURE_DATA, SECTION, VERTEX_STORAGE, VERTEX_DATA,
        MATERIAL_STORAGE, MATERIAL_DATA, INDEX_STORAGE, INDEX_DATA, GEOMETRY_LAYOUT, MARKER_STORAGE, MARKER_DATA, MARKER_LAYOUT, SECTION_DONE, FINISHING,
        READY, TAKEN, CANCELLED, FAILED, CLOSED }
    public record Progress(Phase phase,int validatedSections,int totalSections,int validatedFaces,int totalFaces,int completedSections,long uploadedBytes,long totalBytes,long copiedBytes,long totalCopyBytes) {
        public Progress(Phase phase,int validatedSections,int totalSections,int validatedFaces,int totalFaces,int completedSections,long uploadedBytes,long totalBytes) { this(phase,validatedSections,totalSections,validatedFaces,totalFaces,completedSections,uploadedBytes,totalBytes,0,0); }
    }
    public record Step(Progress progress,int transferBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds,int copiedBytes) {
        public Step(Progress progress,int transferBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds) { this(progress,transferBytes,storageBytes,actions,workUnits,elapsedNanoseconds,0); }
    }
    private static final int CHUNK_BYTES=65536;
    private record Materials(WorldTint tint,WorldLighting lighting,AtlasRaster raster) {}
    private static long gpuBytes(WorldScene scene,AtlasRaster raster,WorldLighting lighting) {
        return (long)scene.usage().faces()*(MeshPacket.PACKED_BYTES_PER_QUAD+16+(lighting==null?0:48))+(long)scene.usage().markers()*12+raster.rgbaBytes();
    }
    private final GlRenderer renderer;private final GlDevice device;private final WorldSnapshot.Source source;
    private final int totalSections,totalFaces,materialWords,materialBytes;private final long totalBytes,totalCopyBytes,gpuBytes;
    private GlScene geometrySource;private GlAtlasFrame atlasSource;private AtlasRaster raster;private GlDevice.Allocation framebuffer;
    private WorldScene scene;private WorldTint tint;private WorldLighting lighting;private int translucentFaces,tintedFaces;private GlDevice.Allocation allocation;private GlScene completed;private final List<GlScene.Section> sections;
    private Phase phase=Phase.VALIDATING;private int validatedSection,validatedFace,validatedPage,geometryCount,markerSections;
    private long markerBytes,uploadedBytes,copiedBytes;private int page,pageRow,section,bufferSlot,vaoSlot,geometryVao,markerVao,vertexBuffer,materialBuffer,indexBuffer,markerBuffer,vertexWordOffset,materialWordOffset,indexOffset,markerOffset;
    private GlUpload(GlRenderer renderer,WorldScene scene,WorldTint tint,WorldLighting lighting,GlDevice.Allocation allocation,GlDevice.Allocation framebuffer,GlScene geometrySource,GlAtlasFrame atlasSource,AtlasRaster raster) {
        this.renderer=renderer;device=renderer.device;this.scene=scene;this.tint=tint;this.lighting=lighting;this.allocation=allocation;materialWords=lighting==null?1:4;materialBytes=materialWords*4;this.geometrySource=geometrySource;this.atlasSource=atlasSource;this.raster=raster;source=scene.snapshot().source();
        // CPU meshBytes also includes face bounds/translucent centers; only packed geometry reaches GPU storage.
        totalSections=scene.sections().size();totalFaces=scene.usage().faces();gpuBytes=gpuBytes(scene,raster,lighting);totalCopyBytes=geometrySource==null?0:(long)totalFaces*MeshPacket.PACKED_BYTES_PER_QUAD+(long)scene.usage().markers()*12+raster.rgbaBytes();totalBytes=gpuBytes-totalCopyBytes;this.framebuffer=framebuffer;sections=new ArrayList<>(totalSections);
    }
    /** Fresh nonnull leases transfer on every outcome; re-transferring a device-owned lease is rejected intact. */
    public static GlUpload begin(GlRenderer renderer,WorldScene.Result owner) { return begin(renderer,owner,null); }
    public static GlUpload begin(GlRenderer renderer,WorldScene.Result owner,WorldTint tint) { return begin(renderer,owner,tint,null); }
    /** Consumes fresh scene/input owners; lighting admission persists through device-journal admission. */
    public static GlUpload beginLit(GlRenderer renderer,WorldScene.Result owner,WorldTint tint,RenderInput<WorldLighting> input) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(owner);Objects.requireNonNull(input);boolean handedOff=false,alreadyOwned=false;GlUpload result=null;
        try(input) {
            var device=renderer.device;alreadyOwned=device.ownsCpu(owner);if(alreadyOwned)throw new GlFailure(GlFailure.Code.STATE,"This scene lease was already transferred to the device");
            try(var source=input.retain()) {
                var memory=device.memory().orElse(null);if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Lighting requires the same managed device coordinator");
                var lighting=source.value();handedOff=true;result=begin(renderer,owner,tint,lighting);
            }
        }catch(RuntimeException|Error failure) {
            if(result!=null)try { result.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(!handedOff&&!alreadyOwned)try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            throw failure;
        }
        return result;
    }
    /** Borrowed constant-time preflight. Returns resolved tint without consuming or admitting the source. */
    static WorldTint validate(GlRenderer renderer,WorldScene.Result owner,WorldTint tint,WorldLighting lighting) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(owner);renderer.device.live(renderer.allocation);
        var scene=owner.scene().orElseThrow(()->new GlFailure(GlFailure.Code.STATE,"A complete CPU scene is required"));
        if(tint==null&&scene.plan().models().tints().hasColors())tint=WorldTint.fromInput(scene);
        if(tint!=null&&tint.requiresPlaceholder())throw new GlFailure(GlFailure.Code.MATERIAL,"A complete native tint capture is required");
        if(tint!=null&&tint.scene()!=scene)throw new GlFailure(GlFailure.Code.STALE,"Native tint must match the exact CPU scene");
        if(lighting!=null&&lighting.requiresPlaceholder())throw new GlFailure(GlFailure.Code.MATERIAL,"A complete lighting capture is required");
        if(lighting!=null&&lighting.scene()!=scene)throw new GlFailure(GlFailure.Code.STALE,"Lighting must match the exact CPU scene");
        if(scene.usage().tintedFaces()>0&&tint==null)throw new GlFailure(GlFailure.Code.MATERIAL,"Explicit native per-vertex tint colors are required for this scene");
        return tint;
    }
    /** Borrow an exact complete source; copy its GPU geometry while uploading fresh tint/final-lighting data. */
    public static GlUpload materials(GlRenderer renderer,GlScene source,WorldTint tint,WorldLighting lighting) { return materials(renderer,source,null,tint,lighting); }
    /** Optional displayed atlas is borrowed and pinned through all queued copies. */
    public static GlUpload materials(GlRenderer renderer,GlScene source,GlAtlasFrame atlas,WorldTint tint,WorldLighting lighting) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(source);source.live();if(source.device!=renderer.device)throw new GlFailure(GlFailure.Code.CONTEXT,"Material refresh requires the same renderer device");
        if(atlas!=null)atlas.requireScene(source);GlScene retained=null;GlAtlasFrame retainedAtlas=null;WorldScene.Result owner=null;boolean handedOff=false;
        try {
            retained=source.retain();if(atlas!=null)retainedAtlas=atlas.retain(retained);owner=source.retainCpuScene();handedOff=true;return begin(renderer,owner,tint,lighting,retained,retainedAtlas);
        }catch(RuntimeException|Error failure) {
            if(retainedAtlas!=null)try { retainedAtlas.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(retained!=null)try { retained.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(owner!=null&&!handedOff)try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    /** Consumes fresh lighting ownership through admission, including mismatch/rejection. */
    public static GlUpload materialsLit(GlRenderer renderer,GlScene source,WorldTint tint,RenderInput<WorldLighting> input) { return materialsLit(renderer,source,null,tint,input); }
    public static GlUpload materialsLit(GlRenderer renderer,GlScene source,GlAtlasFrame atlas,WorldTint tint,RenderInput<WorldLighting> input) {
        Objects.requireNonNull(input);GlUpload result=null;
        try(input;var owner=input.retain()) {
            Objects.requireNonNull(renderer);var memory=renderer.device.memory().orElse(null);
            if(owner.admission().tracked()&&(memory==null||!owner.admission().belongsTo(memory)))throw new IllegalArgumentException("Material refresh requires the same shared lighting coordinator");
            result=materials(renderer,source,atlas,tint,owner.value());
        }catch(RuntimeException|Error failure) { if(result!=null)try { result.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
        return result;
    }
    public static GlUpload begin(GlRenderer renderer,WorldScene.Result owner,WorldTint tint,WorldLighting lighting) { return begin(renderer,owner,tint,lighting,null,null); }
    private static GlUpload begin(GlRenderer renderer,WorldScene.Result owner,WorldTint tint,WorldLighting lighting,GlScene geometrySource,GlAtlasFrame atlasSource) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(owner);var device=renderer.device;GlDevice.Allocation allocation=null,framebuffer=null;boolean alreadyOwned=false;
        try {
            alreadyOwned=device.ownsCpu(owner);if(alreadyOwned)throw new GlFailure(GlFailure.Code.STATE,"This CPU result lease was already transferred to the device");
            tint=validate(renderer,owner,tint,lighting);var scene=owner.scene().orElseThrow();var raster=atlasSource!=null?atlasSource.raster():geometrySource!=null?geometrySource.raster():scene.raster();long extra=0;
            if(raster.requiresPlaceholder()||raster.layout()!=scene.layout()||raster.pages().size()!=scene.raster().pages().size())throw new GlFailure(GlFailure.Code.STALE,"Material atlas must match the exact original scene layout");
            for(int page=0;page<raster.pages().size();page++) {
                var image=raster.pages().get(page);if(image!=scene.raster().pages().get(page))extra+=image.bytes();
                if(image.width()>device.maximumTextureSize()||image.height()>device.maximumTextureSize()||image.width()*4>device.limits().stagingBytes())throw new GlFailure(GlFailure.Code.CAPABILITY,"Atlas exceeds hardware or bounded row-staging dimensions");
            }
            var usage=scene.usage();long cpu=usage.knownPayloadBytes()+extra+(tint==null?0:tint.bytes())+(lighting==null?0:lighting.bytes()),gpu=gpuBytes(scene,raster,lighting);int buffers=usage.faceSections()*3+usage.markerSections(),textures=raster.pages().size(),vaos=usage.faceSections()+usage.markerSections();
            allocation=buffers+textures+vaos==0?device.reserve(0,cpu,0,0,0,0,0,0,0,owner):device.reserveStorage(gpu,cpu,buffers,textures,vaos,0,0,0,0,owner);
            allocation.retained=new Materials(tint,lighting,raster);device.depend(allocation,renderer.allocation);
            if(geometrySource!=null&&!raster.pages().isEmpty())framebuffer=device.reserveStorage(0,0,0,0,0,1,0,0,0);
            return new GlUpload(renderer,scene,tint,lighting,allocation,framebuffer,geometrySource,atlasSource,raster);
        }catch(RuntimeException|Error failure) {
            if(framebuffer!=null)try { release(device,framebuffer); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            try { if(allocation==null) { if(!alreadyOwned)owner.close(); }else release(device,allocation); }
            catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    public WorldSnapshot.Source source() { return source; }
    public Progress progress() { return new Progress(phase,validatedSection,totalSections,validatedFace,totalFaces,section,uploadedBytes,totalBytes,copiedBytes,totalCopyBytes); }
    public boolean ready() { return phase==Phase.READY; }
    boolean ownsResources() { return allocation!=null||geometrySource!=null||atlasSource!=null||framebuffer!=null; }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Incremental GPU upload cancelled"); }
    private boolean gpuPhase() {
        return switch(phase) { case READ_FRAMEBUFFER,TEXTURE_STORAGE,TEXTURE_DATA,VERTEX_STORAGE,VERTEX_DATA,MATERIAL_STORAGE,MATERIAL_DATA,INDEX_STORAGE,INDEX_DATA,GEOMETRY_LAYOUT,MARKER_STORAGE,MARKER_DATA,MARKER_LAYOUT->true;default->false; };
    }
    public Step step(GlUploadBudget budget,BooleanSupplier cancelled) {
        Objects.requireNonNull(budget);Objects.requireNonNull(cancelled);device.current();
        if(phase==Phase.READY)return new Step(progress(),0,0,0,0,0);
        if(allocation==null||phase==Phase.FAILED||phase==Phase.CANCELLED)throw new GlFailure(GlFailure.Code.STATE,"Upload is no longer resumable");device.live(allocation);
        try { return advance(budget,cancelled); }
        catch(RuntimeException|Error failure) {
            try { dispose(failure instanceof CancellationException?Phase.CANCELLED:Phase.FAILED); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    private Step advance(GlUploadBudget budget,BooleanSupplier cancelled) {
        long start=System.nanoTime(),storage=0;int bytes=0,copied=0,actions=0,work=0;boolean finish=false;GlTransferState state=null;
        Throwable failure=null;
        try {
            device.live(renderer.allocation);check(cancelled);
            units:while(work<budget.workUnits()&&(work==0||System.nanoTime()-start<budget.nanoseconds())) {
                check(cancelled);boolean gpu=gpuPhase();if(gpu&&actions>=budget.actions())break;
                if(gpu&&state==null) { state=new GlTransferState(device);GlTransferState.unpack();glActiveTexture(GL_TEXTURE0);glBindSampler(0,0); }
                if(gpu&&phase!=Phase.READ_FRAMEBUFFER&&allocation.unstartedStorage)device.beginStorage(allocation);
                int transferred=0,copy=0;
                switch(phase) {
                    case VALIDATING -> validateUnit();
                    case RESERVING -> {
                        if(validatedFace!=totalFaces||translucentFaces!=scene.usage().translucentFaces()||tintedFaces!=scene.usage().tintedFaces()||markerBytes!=(long)scene.usage().markers()*12)throw new GlFailure(GlFailure.Code.STATE,"Upload validation counts disagree with the CPU scene");
                        if(allocation.gpu!=gpuBytes||allocation.buffers.length!=geometryCount*3+markerSections||allocation.textures.length!=raster.pages().size()||allocation.vaos.length!=geometryCount+markerSections)throw new GlFailure(GlFailure.Code.STATE,"Validated world storage differs from complete admission");
                        phase=raster.pages().isEmpty()?Phase.SECTION:geometrySource==null?Phase.TEXTURE_STORAGE:Phase.READ_FRAMEBUFFER;
                    }
                    case READ_FRAMEBUFFER -> { device.beginStorage(framebuffer);framebuffer.framebuffers[0]=glGenFramebuffers();device.check("world atlas copy framebuffer");phase=Phase.TEXTURE_STORAGE; }
                    case TEXTURE_STORAGE -> {
                        var image=raster.pages().get(page);allocation.textures[page]=glGenTextures();glBindTexture(GL_TEXTURE_2D,allocation.textures[page]);
                        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_BASE_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);
                        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,image.width(),image.height(),0,GL_RGBA,GL_UNSIGNED_BYTE,0L);device.check("incremental texture storage");storage+=image.bytes();pageRow=0;phase=Phase.TEXTURE_DATA;
                    }
                    case TEXTURE_DATA -> {
                        var image=raster.pages().get(page);int rowBytes=image.width()*4,capacity=Math.min(CHUNK_BYTES,Math.min(device.limits().stagingBytes(),budget.transferBytes()-bytes-copied)),rows=Math.min(image.height()-pageRow,capacity/rowBytes);
                        if(rows==0)break units;
                        if(geometrySource!=null) { copyPage(image.width(),rows);copy=rows*rowBytes; }
                        else { transferred=rows*rowBytes;var stage=device.staging();stage.limit(transferred);stage.put(image.pixels().slice(pageRow*rowBytes,transferred));stage.flip();glBindTexture(GL_TEXTURE_2D,allocation.textures[page]);glTexSubImage2D(GL_TEXTURE_2D,0,0,pageRow,image.width(),rows,GL_RGBA,GL_UNSIGNED_BYTE,stage);device.check("incremental texture rows"); }pageRow+=rows;
                        if(pageRow==image.height()) { page++;phase=page<raster.pages().size()?Phase.TEXTURE_STORAGE:Phase.SECTION; }
                    }
                    case SECTION -> {
                        if(section==totalSections) { phase=Phase.FINISHING;break; }
                        geometryVao=0;markerVao=0;vertexBuffer=0;indexBuffer=0;markerBuffer=0;var mesh=scene.sections().get(section);phase=mesh.indexCount()>0?Phase.VERTEX_STORAGE:mesh.markers().isEmpty()?Phase.SECTION_DONE:Phase.MARKER_STORAGE;
                    }
                    case VERTEX_STORAGE -> {
                        var mesh=scene.sections().get(section);allocation.vaos[vaoSlot++]=geometryVao=glGenVertexArrays();glBindVertexArray(geometryVao);allocation.buffers[bufferSlot++]=vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);long size=(long)mesh.vertexCount()*MeshPacket.STRIDE_FLOATS*4;glBufferData(GL_ARRAY_BUFFER,size,GL_STATIC_DRAW);device.check("incremental vertex storage");storage+=size;vertexWordOffset=0;phase=Phase.VERTEX_DATA;
                    }
                    case VERTEX_DATA -> {
                        var mesh=scene.sections().get(section);int words=mesh.vertexCount()*MeshPacket.STRIDE_FLOATS,count=Math.min(words-vertexWordOffset,chunk(budget.transferBytes()-bytes-copied)/4);if(count==0)break units;
                        if(geometrySource!=null) { copyBuffer(geometrySource.sections.get(section).vertexBuffer(),vertexBuffer,(long)vertexWordOffset*4,(long)count*4);copy=count*4; }
                        else { var stage=device.staging();stage.limit(count*4);var values=stage.asFloatBuffer();values.put(mesh.vertexBuffer().slice(vertexWordOffset,count));glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferSubData(GL_ARRAY_BUFFER,(long)vertexWordOffset*4,stage);device.check("incremental vertex data");transferred=count*4; }
                        vertexWordOffset+=count;if(vertexWordOffset==words)phase=Phase.MATERIAL_STORAGE;
                    }
                    case MATERIAL_STORAGE -> {
                        var mesh=scene.sections().get(section);allocation.buffers[bufferSlot++]=materialBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,materialBuffer);long size=(long)mesh.vertexCount()*materialBytes;glBufferData(GL_ARRAY_BUFFER,size,GL_STATIC_DRAW);device.check("incremental material storage");storage+=size;materialWordOffset=0;phase=Phase.MATERIAL_DATA;
                    }
                    case MATERIAL_DATA -> {
                        var mesh=scene.sections().get(section);int words=mesh.vertexCount()*materialWords,count=Math.min(words-materialWordOffset,chunk(budget.transferBytes()-bytes-copied)/4);if(count==0)break units;
                        var stage=device.staging();stage.limit(count*4);
                        for(int i=0;i<count;i++) {
                            if((i&1023)==0)check(cancelled);int word=materialWordOffset+i,vertex=word/materialWords,component=word%materialWords;
                            if(component==0) { var material=mesh.faces().get(vertex/4).source().source();int rgb=material.material().multiplyRgb(tint==null?0xffffff:tint.rgb(section,vertex/4,vertex%4));int flags=(material.lightEmission()<<3)|material.shadeDirectionOverride().map(value->value.ordinal()+1).orElse(0);stage.put((byte)(rgb>>>16)).put((byte)(rgb>>>8)).put((byte)rgb).put((byte)flags); }
                            else stage.putFloat(lighting.component(section,vertex/4,vertex%4,component-1));
                        }
                        stage.flip();glBindBuffer(GL_ARRAY_BUFFER,materialBuffer);glBufferSubData(GL_ARRAY_BUFFER,(long)materialWordOffset*4,stage);device.check("incremental material data");materialWordOffset+=count;transferred=count*4;if(materialWordOffset==words)phase=Phase.INDEX_STORAGE;
                    }
                    case INDEX_STORAGE -> {
                        var mesh=scene.sections().get(section);glBindVertexArray(geometryVao);allocation.buffers[bufferSlot++]=indexBuffer=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indexBuffer);long size=(long)mesh.indexCount()*4;glBufferData(GL_ELEMENT_ARRAY_BUFFER,size,GL_STATIC_DRAW);device.check("incremental index storage");storage+=size;indexOffset=0;phase=Phase.INDEX_DATA;
                    }
                    case INDEX_DATA -> {
                        var source=scene.sections().get(section).indexBuffer();int count=Math.min(source.remaining()-indexOffset,chunk(budget.transferBytes()-bytes-copied)/4);if(count==0)break units;
                        if(geometrySource!=null) { copyBuffer(geometrySource.sections.get(section).indexBuffer(),indexBuffer,(long)indexOffset*4,(long)count*4);copy=count*4; }
                        else { var stage=device.staging();stage.limit(count*4);var values=stage.asIntBuffer();values.put(source.slice(indexOffset,count));glBindBuffer(GL_ARRAY_BUFFER,indexBuffer);glBufferSubData(GL_ARRAY_BUFFER,(long)indexOffset*4,stage);device.check("incremental index data");transferred=count*4; }
                        indexOffset+=count;if(indexOffset==source.remaining())phase=Phase.GEOMETRY_LAYOUT;
                    }
                    case GEOMETRY_LAYOUT -> {
                        glBindVertexArray(geometryVao);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glVertexAttribPointer(0,3,GL_FLOAT,false,32,0L);glEnableVertexAttribArray(0);glVertexAttribPointer(1,3,GL_FLOAT,false,32,12L);glEnableVertexAttribArray(1);glVertexAttribPointer(2,2,GL_FLOAT,false,32,24L);glEnableVertexAttribArray(2);
                        glBindBuffer(GL_ARRAY_BUFFER,materialBuffer);glVertexAttribPointer(4,3,GL_UNSIGNED_BYTE,true,materialBytes,0L);glEnableVertexAttribArray(4);glVertexAttribIPointer(6,1,GL_UNSIGNED_BYTE,materialBytes,3L);glEnableVertexAttribArray(6);if(lighting!=null) { glVertexAttribPointer(5,3,GL_FLOAT,false,materialBytes,4L);glEnableVertexAttribArray(5); }device.check("incremental geometry/material layout");phase=scene.sections().get(section).markers().isEmpty()?Phase.SECTION_DONE:Phase.MARKER_STORAGE;
                    }
                    case MARKER_STORAGE -> {
                        var mesh=scene.sections().get(section);allocation.vaos[vaoSlot++]=markerVao=glGenVertexArrays();glBindVertexArray(markerVao);glBindBuffer(GL_ARRAY_BUFFER,renderer.allocation.buffers[0]);glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,renderer.allocation.buffers[1]);allocation.buffers[bufferSlot++]=markerBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,markerBuffer);long size=(long)mesh.markers().size()*12;glBufferData(GL_ARRAY_BUFFER,size,GL_STATIC_DRAW);device.check("incremental marker storage");storage+=size;markerOffset=0;phase=Phase.MARKER_DATA;
                    }
                    case MARKER_DATA -> {
                        var mesh=scene.sections().get(section);int count=Math.min(mesh.markers().size()-markerOffset,chunk(budget.transferBytes()-bytes-copied)/12);if(count==0)break units;
                        if(geometrySource!=null) { copyBuffer(geometrySource.sections.get(section).markerBuffer(),markerBuffer,(long)markerOffset*12,(long)count*12);copy=count*12; }
                        else {
                            var stage=device.staging();stage.limit(count*12);var values=stage.asFloatBuffer();var origin=mesh.origin();
                            for(int i=0;i<count;i++) { if((i&1023)==0)check(cancelled);var p=mesh.markers().get(markerOffset+i).position();values.put((float)(p.x()-origin.x())).put((float)((long)p.y()-origin.y())).put((float)(p.z()-origin.z())); }
                            glBindBuffer(GL_ARRAY_BUFFER,markerBuffer);glBufferSubData(GL_ARRAY_BUFFER,(long)markerOffset*12,stage);device.check("incremental marker data");transferred=count*12;
                        }markerOffset+=count;if(markerOffset==mesh.markers().size())phase=Phase.MARKER_LAYOUT;
                    }
                    case MARKER_LAYOUT -> { glBindVertexArray(markerVao);glBindBuffer(GL_ARRAY_BUFFER,markerBuffer);glVertexAttribPointer(3,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(3);glVertexAttribDivisor(3,1);device.check("incremental marker layout");phase=Phase.SECTION_DONE; }
                    case SECTION_DONE -> { var mesh=scene.sections().get(section);sections.add(new GlScene.Section(mesh.origin(),mesh.bounds(),mesh.ranges(),geometryVao,markerVao,mesh.markers().size(),vertexBuffer,indexBuffer,markerBuffer));section++;phase=Phase.SECTION; }
                    case FINISHING -> { if(uploadedBytes!=totalBytes||copiedBytes!=totalCopyBytes||bufferSlot!=allocation.buffers.length||vaoSlot!=allocation.vaos.length||sections.size()!=totalSections)throw new GlFailure(GlFailure.Code.STATE,"GPU upload did not cover the complete admitted scene");device.check("complete incremental upload");finish=true; }
                    default -> throw new GlFailure(GlFailure.Code.STATE,"Unexpected upload phase");
                }
                bytes+=transferred;uploadedBytes+=transferred;copied+=copy;copiedBytes+=copy;work++;if(gpu)actions++;if(finish)break;
            }
        }catch(RuntimeException|Error error) { failure=error;throw error; }
        finally {
            if(state!=null)try { state.close(); }
            catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        }
        if(finish) { check(cancelled);releaseFramebuffer();releaseSource();check(cancelled);var lightingInfo=lighting==null?GlScene.LightingInfo.SIMPLE:new GlScene.LightingInfo(true,lighting.policy());completed=new GlScene(device,allocation,sections,translucentFaces,lightingInfo,raster);raster=null;scene=null;tint=null;lighting=null;sections.clear();phase=Phase.READY; }
        return result(start,bytes,copied,storage,actions,work);
    }
    private int chunk(int remaining) { return Math.min(CHUNK_BYTES,Math.min(device.limits().stagingBytes(),remaining)); }
    private Step result(long start,int bytes,int copied,long storage,int actions,int work) { return new Step(progress(),bytes,storage,actions,work,System.nanoTime()-start,copied); }
    private void copyBuffer(int source,int target,long offset,long bytes) {
        geometrySource.live();glBindBuffer(GL_COPY_READ_BUFFER,source);glBindBuffer(GL_COPY_WRITE_BUFFER,target);glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,offset,offset,bytes);device.check("bounded world geometry copy");
    }
    private void copyPage(int width,int rows) {
        geometrySource.live();glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer.framebuffers[0]);glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,atlasSource==null?geometrySource.allocation.textures[page]:atlasSource.texture(page),0);Throwable failure=null;
        try {
            glReadBuffer(GL_COLOR_ATTACHMENT0);if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new GlFailure(GlFailure.Code.GL_ERROR,"World atlas source framebuffer is incomplete");
            glBindTexture(GL_TEXTURE_2D,allocation.textures[page]);glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,pageRow,0,pageRow,width,rows);device.check("bounded world atlas copy");
        }catch(RuntimeException|Error error) { failure=error;throw error; }
        finally { try { glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,0,0);device.check("world atlas source detachment"); }catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); } }
    }
    private static void release(GlDevice device,GlDevice.Allocation allocation) { if(allocation.unstartedStorage)device.discardUnstarted(allocation);else device.retire(allocation); }
    private void releaseFramebuffer() { if(framebuffer!=null)try { release(device,framebuffer); }finally { if(framebuffer.retired||framebuffer.released)framebuffer=null; } }
    private void releaseSource() {
        Throwable failure=null;
        if(atlasSource!=null)try { atlasSource.close();atlasSource=null; }catch(RuntimeException|Error cleanup) { if(atlasSource.closedOrReleased())atlasSource=null;failure=cleanup; }
        if(geometrySource!=null)try { geometrySource.close();geometrySource=null; }catch(RuntimeException|Error cleanup) { if(geometrySource.closed())geometrySource=null;if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    private void validateUnit() {
        if(validatedSection<totalSections) {
            var mesh=scene.sections().get(validatedSection);mesh.requireRaster(raster);var usage=mesh.usage().orElseThrow();
            if(usage.tintedFaces()>0&&tint==null)throw new GlFailure(GlFailure.Code.MATERIAL,"Explicit native per-vertex tint colors are required for this scene");
            if(mesh.indexCount()>0)geometryCount++;if(!mesh.markers().isEmpty())markerSections++;markerBytes+=(long)mesh.markers().size()*12;
            validatedFace+=usage.faces();translucentFaces+=usage.translucentFaces();tintedFaces+=usage.tintedFaces();validatedSection++;
        } else if(validatedPage<raster.pages().size()) {
            var image=raster.pages().get(validatedPage++);if(image.width()>device.maximumTextureSize()||image.height()>device.maximumTextureSize()||image.width()*4>device.limits().stagingBytes())throw new GlFailure(GlFailure.Code.CAPABILITY,"Atlas exceeds hardware or bounded row-staging dimensions");
        } else phase=Phase.RESERVING;
    }
    public GlScene take() {
        device.current();if(phase!=Phase.READY||completed==null)throw new GlFailure(GlFailure.Code.STATE,"No complete GPU upload is available");var result=completed;completed=null;allocation=null;phase=Phase.TAKEN;return result;
    }
    private void dispose(Phase terminal) {
        Throwable failure=null;try { releaseFramebuffer(); }catch(RuntimeException|Error error) { failure=error; }
        try { if(allocation!=null)release(device,allocation); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        finally { if(allocation==null||allocation.retired||allocation.released) { allocation=null;scene=null;raster=null;tint=null;lighting=null;completed=null;sections.clear(); }phase=terminal; }
        try { releaseSource(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    @Override public void close() { if((phase==Phase.TAKEN||phase==Phase.CLOSED)&&!ownsResources())return;dispose(Phase.CLOSED); }
}
