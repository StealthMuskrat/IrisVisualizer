package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Opaque, simply lit scalar display bins. No Minecraft state/material or noise evaluation. */
public final class GlNoiseRenderer implements AutoCloseable {
    private static final String VERTEX="""
        #version 330 core
        layout(location=0) in uvec3 instance;
        uniform mat4 viewProjection;
        uniform vec3 gridOffset;
        uniform uvec2 gridSize;
        uniform float horizontalScale;
        flat out vec3 faceColor;
        out vec3 worldPosition;
        const int triangle[6]=int[6](0,1,2,0,2,3);
        const vec3 corner[24]=vec3[24](
            vec3(0,0,1),vec3(0,0,0),vec3(1,0,0),vec3(1,0,1),
            vec3(0,1,0),vec3(0,1,1),vec3(1,1,1),vec3(1,1,0),
            vec3(1,1,0),vec3(1,0,0),vec3(0,0,0),vec3(0,1,0),
            vec3(0,1,1),vec3(0,0,1),vec3(1,0,1),vec3(1,1,1),
            vec3(0,1,0),vec3(0,0,0),vec3(0,0,1),vec3(0,1,1),
            vec3(1,1,1),vec3(1,0,1),vec3(1,0,0),vec3(1,1,0));
        const vec3 normal[6]=vec3[6](vec3(0,-1,0),vec3(0,1,0),vec3(0,0,-1),vec3(0,0,1),vec3(-1,0,0),vec3(1,0,0));
        void main() {
            uint cell=instance.x>>3u,side=instance.x&7u;
            vec3 origin=vec3(float(cell%gridSize.x),float(cell/(gridSize.x*gridSize.y)),float((cell/gridSize.x)%gridSize.y));
            float u=float((instance.z&4095u)+1u),v=float((instance.z>>12u)+1u);
            vec3 extent=side<2u?vec3(u,1.0,v):(side<4u?vec3(u,v,1.0):vec3(1.0,v,u));
            vec3 local=(origin+corner[int(side)*4+triangle[gl_VertexID]]*extent)*vec3(horizontalScale,1.0,horizontalScale);
            worldPosition=gridOffset+local;gl_Position=viewProjection*vec4(worldPosition,1.0);
            vec3 rgb=vec3(float((instance.y>>24u)&255u),float((instance.y>>16u)&255u),float((instance.y>>8u)&255u))/255.0;
            faceColor=rgb*(0.35+0.65*max(0.0,dot(normal[int(side)],normalize(vec3(0.4,0.8,0.25)))));
        }
        """;
    private static final String FRAGMENT="""
        #version 330 core
        flat in vec3 faceColor;
        in vec3 worldPosition;
        uniform bool clipEnabled;
        uniform vec3 clipMin;
        uniform vec3 clipMax;
        out vec4 color;
        void main() {
            if(clipEnabled && (any(lessThan(worldPosition,clipMin)) || any(greaterThan(worldPosition,clipMax)))) discard;
            color=vec4(faceColor,1.0);
        }
        """;
    public record Stats(int drawCalls,int submittedFaces,long triangles,int totalBatches,int visibleBatches,int culledBatches,int culledFaces) {
        public Stats(int drawCalls,int submittedFaces,long triangles) { this(drawCalls,submittedFaces,triangles,(submittedFaces+NoiseSurface.BATCH_FACES-1)/NoiseSurface.BATCH_FACES,(submittedFaces+NoiseSurface.BATCH_FACES-1)/NoiseSurface.BATCH_FACES,0,0); }
        public Stats { if(drawCalls<0||submittedFaces<0||submittedFaces>1048576||culledFaces>1048576-submittedFaces||triangles!=2L*submittedFaces||totalBatches>1024||totalBatches<0||visibleBatches<0||culledBatches<0||culledFaces<0||(long)visibleBatches+culledBatches!=totalBatches||drawCalls>visibleBatches)throw new IllegalArgumentException("Invalid scalar submission counts"); }
    }
    final GlDevice device;final GlDevice.Allocation allocation;private final int matrix,offset,size,scale,clipEnabled,clipMin,clipMax;
    private GlNoiseRenderer(GlDevice device,GlDevice.Allocation allocation) { this.device=device;this.allocation=allocation;matrix=uniform("viewProjection");offset=uniform("gridOffset");size=uniform("gridSize");scale=uniform("horizontalScale");clipEnabled=uniform("clipEnabled");clipMin=uniform("clipMin");clipMax=uniform("clipMax"); }
    private int uniform(String name) { int value=glGetUniformLocation(allocation.programs[0],name);if(value<0)throw new GlFailure(GlFailure.Code.SHADER,"Missing scalar uniform: "+name);return value; }
    public static GlNoiseRenderer create(GlDevice device) {
        Objects.requireNonNull(device);var allocation=device.reserve(0,0,0,0,0,0,0,1,2,null);
        try(var state=new GlTransferState(device)) {
            String[] sources={VERTEX,FRAGMENT};int[] types={GL_VERTEX_SHADER,GL_FRAGMENT_SHADER};
            for(int i=0;i<2;i++) { allocation.shaders[i]=glCreateShader(types[i]);glShaderSource(allocation.shaders[i],sources[i]);glCompileShader(allocation.shaders[i]);if(glGetShaderi(allocation.shaders[i],GL_COMPILE_STATUS)==GL_FALSE)throw new GlFailure(GlFailure.Code.SHADER,"Scalar shader failed: "+glGetShaderInfoLog(allocation.shaders[i],4096)); }
            allocation.programs[0]=glCreateProgram();for(int shader:allocation.shaders)glAttachShader(allocation.programs[0],shader);glLinkProgram(allocation.programs[0]);if(glGetProgrami(allocation.programs[0],GL_LINK_STATUS)==GL_FALSE)throw new GlFailure(GlFailure.Code.SHADER,"Scalar program failed: "+glGetProgramInfoLog(allocation.programs[0],4096));
            var result=new GlNoiseRenderer(device,allocation);device.check("scalar renderer creation");return result;
        }catch(RuntimeException|Error failure) { try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    public Stats render(GlNoiseScene scene,GlTarget target,GlCamera camera) { return render(scene,target,camera,WorldClip.NONE); }
    // Scalar local span and camera offset are each <=2^20; saturated planes stay strictly beyond them.
    private static float plane(double value) { return (float)Math.clamp(value,-4194304,4194304); }
    public Stats render(GlNoiseScene scene,GlTarget target,GlCamera camera,WorldClip clip) {
        Objects.requireNonNull(clip);Objects.requireNonNull(scene);Objects.requireNonNull(target);Objects.requireNonNull(camera);device.live(allocation);
        if(scene.device!=device||target.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Scalar draw inputs must share their original device");device.live(target.allocation);
        var surface=scene.surface();var grid=surface.volume().grid();float[] transform=camera.matrix((double)target.width()/target.height()),relative=new float[3];camera.offset(new WorldSnapshot.Position(grid.horizontal().originX(),grid.vertical().minY(),grid.horizontal().originZ()),relative,0);
        float step=(float)grid.horizontal().blocksPerSample();double magnitude=Math.max(Math.abs((double)relative[0])+grid.width()*(double)step,Math.abs((double)relative[2])+grid.depth()*(double)step);
        if(step<4*Math.ulp((float)magnitude))throw new GlFailure(GlFailure.Code.LIMIT,"Scalar sample spacing is too small for this camera anchor's float precision");
        var clipBounds=clip.relative(camera.anchor());var frustum=new GlFrustum(transform);int calls=0,submitted=0,visible=0,culled=0,culledFaces=0;
        try(var state=new GlState(device)) {
            state.drawing();target.complete=false;glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target.allocation.framebuffers[0]);glViewport(0,0,target.width(),target.height());glClearBufferfv(GL_COLOR,0,new float[]{0.08f,0.1f,0.14f,1});glClearBufferfv(GL_DEPTH,0,new float[]{1});
            if(surface.size()>0) { glUseProgram(allocation.programs[0]);glUniformMatrix4fv(matrix,false,transform);glUniform3f(offset,relative[0],relative[1],relative[2]);glUniform2ui(size,grid.width(),grid.depth());glUniform1f(scale,step);glUniform1i(clipEnabled,clip.enabled()?1:0);
                if(clip.enabled()) { glUniform3f(clipMin,plane(clipBounds.minX()),plane(clipBounds.minY()),plane(clipBounds.minZ()));glUniform3f(clipMax,plane(clipBounds.maxX()),plane(clipBounds.maxY()),plane(clipBounds.maxZ())); }
                glBindVertexArray(scene.allocation.vaos[0]);glBindBuffer(GL_ARRAY_BUFFER,scene.allocation.buffers[0]);
                int first=-1,count=0;
                for(int batch=0;batch<=surface.batchCount();batch++) {
                    boolean show=batch<surface.batchCount()&&frustum.visible(surface,batch,step,relative,clip.enabled()?clipBounds:null);
                    if(show) { visible++;if(first<0)first=surface.batchFirst(batch);count+=surface.batchSize(batch); }
                    else {
                        if(batch<surface.batchCount()) { culled++;culledFaces+=surface.batchSize(batch); }
                        if(first>=0) { glVertexAttribIPointer(0,3,GL_UNSIGNED_INT,GlNoiseUpload.INSTANCE_BYTES,(long)first*GlNoiseUpload.INSTANCE_BYTES);glDrawArraysInstanced(GL_TRIANGLES,0,6,count);calls++;submitted+=count;first=-1;count=0; }
                    }
                }
            }device.check("complete scalar draw");
        }
        target.complete=true;return new Stats(calls,submitted,(long)submitted*2,surface.batchCount(),visible,culled,culledFaces);
    }
    @Override public void close() { device.retire(allocation); }
}
