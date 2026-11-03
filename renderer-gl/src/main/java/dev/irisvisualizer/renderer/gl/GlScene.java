package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Immutable GPU snapshot. Upload consumes the CPU result lease on every outcome. */
public final class GlScene implements AutoCloseable {
    record Section(WorldSnapshot.Position origin,List<AtlasMesh.DrawRange> ranges,int geometryVao,int markerVao,int markerCount) {}
    final GlDevice device;final GlDevice.Allocation allocation;List<Section> sections;
    private GlScene(GlDevice device,GlDevice.Allocation allocation,List<Section> sections) { this.device=device;this.allocation=allocation;this.sections=List.copyOf(sections); }
    private static void cancelled(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("GPU scene upload cancelled"); }
    /** Ownership is transferred by invoking upload; callers must not separately close cpuOwner. */
    public static GlScene upload(GlRenderer renderer,WorldScene.Result cpuOwner,BooleanSupplier cancelled) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(cpuOwner);Objects.requireNonNull(cancelled);var device=renderer.device;GlDevice.Allocation allocation=null;
        try {
            device.live(renderer.allocation);cancelled(cancelled);var scene=cpuOwner.scene().orElseThrow(()->new GlFailure(GlFailure.Code.STATE,"A complete successful CPU scene is required"));
            int geometry=0,markers=0;long markerBytes=0;
            for(var section:scene.sections()) {
                cancelled(cancelled);section.requireRaster(scene.raster());if(section.indexCount()>0)geometry++;if(!section.markers().isEmpty())markers++;markerBytes+=(long)section.markers().size()*12;
                for(var face:section.faces()) {
                    cancelled(cancelled);if(face.source().alpha()==TexturePreparation.AlphaClass.TRANSLUCENT||face.source().source().tintIndex()>=0)throw new GlFailure(GlFailure.Code.MATERIAL,"Native tint resolution and translucent rendering must be implemented before uploading these materials");
                }
            }
            for(var page:scene.raster().pages())if(page.width()>device.maximumTextureSize()||page.height()>device.maximumTextureSize()||page.width()*4>device.limits().stagingBytes())throw new GlFailure(GlFailure.Code.CAPABILITY,"Atlas dimensions exceed device or bounded row-staging capacity");
            long gpu=scene.usage().meshBytes()+markerBytes+scene.raster().rgbaBytes();
            allocation=device.reserve(gpu,scene.usage().knownPayloadBytes(),geometry*2+markers,scene.raster().pages().size(),geometry+markers,0,0,0,0,cpuOwner);
            device.depend(allocation,renderer.allocation);
            return transfer(renderer,scene,allocation,cancelled);
        }catch(RuntimeException|Error failure) {
            if(allocation==null)cpuOwner.close();else try { device.retire(allocation); }catch(RuntimeException retirement) { failure.addSuppressed(retirement); }
            throw failure;
        }
    }
    private static GlScene transfer(GlRenderer renderer,WorldScene scene,GlDevice.Allocation allocation,BooleanSupplier cancelled) {
        var device=renderer.device;var sections=new ArrayList<Section>(scene.sections().size());int buffer=0,vao=0;
        try(var state=new GlState(device)) {
            GlState.unpack();glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);
            for(int page=0;page<scene.raster().pages().size();page++) {
                cancelled(cancelled);var image=scene.raster().pages().get(page);allocation.textures[page]=glGenTextures();glBindTexture(GL_TEXTURE_2D,allocation.textures[page]);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_BASE_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,image.width(),image.height(),0,GL_RGBA,GL_UNSIGNED_BYTE,0L);device.check("atlas texture allocation");var pixels=image.pixels();int rowBytes=image.width()*4,rows=Math.max(1,device.limits().stagingBytes()/rowBytes);
                for(int y=0;y<image.height();y+=rows) {
                    cancelled(cancelled);int height=Math.min(rows,image.height()-y),bytes=height*rowBytes;var stage=device.staging();stage.limit(bytes);stage.put(pixels.slice(y*rowBytes,bytes));stage.flip();glTexSubImage2D(GL_TEXTURE_2D,0,0,y,image.width(),height,GL_RGBA,GL_UNSIGNED_BYTE,stage);device.check("bounded atlas row upload");
                }
            }
            for(var source:scene.sections()) {
                cancelled(cancelled);int geometryVao=0,markerVao=0;
                if(source.indexCount()>0) {
                    allocation.vaos[vao++]=geometryVao=glGenVertexArrays();glBindVertexArray(geometryVao);
                    int vertex=buffer++;allocation.buffers[vertex]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[vertex]);floats(device,GL_ARRAY_BUFFER,source.vertexBuffer(),cancelled);
                    int index=buffer++;allocation.buffers[index]=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,allocation.buffers[index]);integers(device,GL_ELEMENT_ARRAY_BUFFER,source.indexBuffer(),cancelled);
                    glVertexAttribPointer(0,3,GL_FLOAT,false,32,0L);glEnableVertexAttribArray(0);glVertexAttribPointer(1,3,GL_FLOAT,false,32,12L);glEnableVertexAttribArray(1);glVertexAttribPointer(2,2,GL_FLOAT,false,32,24L);glEnableVertexAttribArray(2);device.check("geometry vertex layout");
                }
                if(!source.markers().isEmpty()) {
                    allocation.vaos[vao++]=markerVao=glGenVertexArrays();glBindVertexArray(markerVao);glBindBuffer(GL_ARRAY_BUFFER,renderer.allocation.buffers[0]);glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,renderer.allocation.buffers[1]);
                    int instance=buffer++;allocation.buffers[instance]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[instance]);glBufferData(GL_ARRAY_BUFFER,(long)source.markers().size()*12,GL_STATIC_DRAW);device.check("marker instance allocation");
                    for(int start=0;start<source.markers().size();) {
                        cancelled(cancelled);int count=Math.min(device.limits().stagingBytes()/12,source.markers().size()-start);var stage=device.staging();stage.limit(count*12);var values=stage.asFloatBuffer();
                        for(int i=0;i<count;i++) {
                            var position=source.markers().get(start+i).position();var origin=source.origin();values.put((float)(position.x()-origin.x())).put((float)((long)position.y()-origin.y())).put((float)(position.z()-origin.z()));
                        }
                        glBufferSubData(GL_ARRAY_BUFFER,(long)start*12,stage);device.check("bounded marker instance upload");start+=count;
                    }
                    glVertexAttribPointer(3,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(3);glVertexAttribDivisor(3,1);device.check("marker instance layout");
                }
                sections.add(new Section(source.origin(),source.ranges(),geometryVao,markerVao,source.markers().size()));
            }
            cancelled(cancelled);device.check("complete scene upload");return new GlScene(device,allocation,sections);
        }
    }
    static void floats(GlDevice device,int target,FloatBuffer source,BooleanSupplier cancelled) {
        glBufferData(target,(long)source.remaining()*4,GL_STATIC_DRAW);device.check("vertex buffer allocation");
        for(int offset=0;offset<source.remaining();) {
            cancelled(cancelled);int count=Math.min(device.limits().stagingBytes()/4,source.remaining()-offset);var stage=device.staging();stage.limit(count*4);var values=stage.asFloatBuffer();for(int i=0;i<count;i++)values.put(source.get(source.position()+offset+i));
            glBufferSubData(target,(long)offset*4,stage);device.check("bounded vertex upload");offset+=count;
        }
    }
    static void integers(GlDevice device,int target,IntBuffer source,BooleanSupplier cancelled) {
        glBufferData(target,(long)source.remaining()*4,GL_STATIC_DRAW);device.check("index buffer allocation");
        for(int offset=0;offset<source.remaining();) {
            cancelled(cancelled);int count=Math.min(device.limits().stagingBytes()/4,source.remaining()-offset);var stage=device.staging();stage.limit(count*4);var values=stage.asIntBuffer();for(int i=0;i<count;i++)values.put(source.get(source.position()+offset+i));
            glBufferSubData(target,(long)offset*4,stage);device.check("bounded index upload");offset+=count;
        }
    }
    public WorldScene cpuScene() { device.live(allocation);return allocation.owner.scene().orElseThrow(); }
    @Override public void close() { try { device.retire(allocation); }finally { if(allocation.retired||allocation.released)sections=List.of(); } }
}
