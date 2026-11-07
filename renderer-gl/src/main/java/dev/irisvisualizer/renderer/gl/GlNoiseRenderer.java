package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Opaque, simply lit scalar display bins. No Minecraft state/material or noise evaluation. */
public final class GlNoiseRenderer implements AutoCloseable {
    private static final String VERTEX="""
        #version 330 core
        layout(location=0) in uvec2 geometry;
        layout(location=1) in uint rgba;
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
            uint cell=geometry.x>>3u,side=geometry.x&7u;
            vec3 origin=vec3(float(cell%gridSize.x),float(cell/(gridSize.x*gridSize.y)),float((cell/gridSize.x)%gridSize.y));
            float u=float((geometry.y&4095u)+1u),v=float((geometry.y>>12u)+1u);
            vec3 extent=side<2u?vec3(u,1.0,v):(side<4u?vec3(u,v,1.0):vec3(1.0,v,u));
            vec3 local=(origin+corner[int(side)*4+triangle[gl_VertexID]]*extent)*vec3(horizontalScale,1.0,horizontalScale);
            worldPosition=gridOffset+local;gl_Position=viewProjection*vec4(worldPosition,1.0);
            vec3 rgb=vec3(float((rgba>>24u)&255u),float((rgba>>16u)&255u),float((rgba>>8u)&255u))/255.0;
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
    public record Stats(int drawCalls,int submittedFaces,long triangles,int totalBatches,int visibleBatches,int culledBatches,int culledFaces,int boundsTests,int testedBatches) {
        public Stats(int drawCalls,int submittedFaces,long triangles) { this(drawCalls,submittedFaces,triangles,(submittedFaces+NoiseSurface.BATCH_FACES-1)/NoiseSurface.BATCH_FACES,(submittedFaces+NoiseSurface.BATCH_FACES-1)/NoiseSurface.BATCH_FACES,0,0); }
        public Stats(int drawCalls,int submittedFaces,long triangles,int totalBatches,int visibleBatches,int culledBatches,int culledFaces) { this(drawCalls,submittedFaces,triangles,totalBatches,visibleBatches,culledBatches,culledFaces,0,0); }
        public Stats { if(drawCalls<0||submittedFaces<0||submittedFaces>1048576||culledFaces>1048576-submittedFaces||triangles!=2L*submittedFaces||totalBatches>1024||totalBatches<0||visibleBatches<0||culledBatches<0||culledFaces<0||(long)visibleBatches+culledBatches!=totalBatches||drawCalls>visibleBatches||boundsTests<0||boundsTests>Math.max(0,2*totalBatches-1)||testedBatches<0||testedBatches>totalBatches||testedBatches>boundsTests)throw new IllegalArgumentException("Invalid scalar submission counts"); }
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
    /** Constant metadata sizing before source/target drawing; excludes retained payload and host state. */
    public long drawScratchBytes(GlNoiseScene scene,boolean rangeCulling) {
        return drawScratchBytes(scene,GlCullingMode.from(rangeCulling));
    }
    public long drawScratchBytes(GlNoiseScene scene,GlCullingMode mode) { Objects.requireNonNull(scene);Objects.requireNonNull(mode);device.live(allocation);if(scene.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Scalar scratch source belongs to another device");return GlNoiseCulling.workingBytes(scene.surface(),mode); }
    public Stats render(GlNoiseScene scene,GlTarget target,GlCamera camera,WorldClip clip) { return render(scene,target,camera,clip,true); }
    /** Disable group rejection for separately rendered differential images; fragment clipping stays active. */
    public Stats render(GlNoiseScene scene,GlTarget target,GlCamera camera,WorldClip clip,boolean rangeCulling) {
        return render(scene,target,camera,clip,GlCullingMode.from(rangeCulling));
    }
    /** Explicit linear reference shares source geometry, clipping and the same admitted mask size. */
    public Stats render(GlNoiseScene scene,GlTarget target,GlCamera camera,WorldClip clip,GlCullingMode mode) {
        Objects.requireNonNull(clip);Objects.requireNonNull(scene);Objects.requireNonNull(target);Objects.requireNonNull(camera);Objects.requireNonNull(mode);device.live(allocation);
        if(scene.device!=device||target.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Scalar draw inputs must share their original device");device.live(target.allocation);var surface=scene.surface();
        var scratch=device.reserve(0,GlNoiseCulling.workingBytes(surface,mode),0,0,0,0,0,0,0,null);Throwable escaping=null;Stats result;
        try { result=drawAdmitted(scene,target,camera,clip,mode,surface); }
        catch(RuntimeException|Error failure) { escaping=failure;throw failure; }
        finally { try { device.retire(scratch); }catch(RuntimeException|Error cleanup) { if(escaping==null)throw cleanup;if(cleanup!=escaping)escaping.addSuppressed(cleanup); } }
        target.complete=true;return result;
    }
    /** Array/math owners unwind before the outer draw releases their covering CPU journal. */
    private Stats drawAdmitted(GlNoiseScene scene,GlTarget target,GlCamera camera,WorldClip clip,GlCullingMode mode,NoiseSurface surface) {
        var culling=new GlNoiseCulling(surface,camera,(double)target.width()/target.height(),clip,mode);var grid=surface.volume().grid();var transform=culling.matrix;var relative=culling.relative;var clipBounds=culling.clip;float step=culling.scale;int calls=0,submitted=0;
        try(var state=new GlState(device)) {
            state.drawing();target.complete=false;glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target.allocation.framebuffers[0]);glViewport(0,0,target.width(),target.height());glClearBufferfv(GL_COLOR,0,new float[]{0.08f,0.1f,0.14f,1});glClearBufferfv(GL_DEPTH,0,new float[]{1});
            if(surface.size()>0) { glUseProgram(allocation.programs[0]);glUniformMatrix4fv(matrix,false,transform);glUniform3f(offset,relative[0],relative[1],relative[2]);glUniform2ui(size,grid.width(),grid.depth());glUniform1f(scale,step);glUniform1i(clipEnabled,clip.enabled()?1:0);
                if(clip.enabled()) { glUniform3f(clipMin,plane(clipBounds.minX()),plane(clipBounds.minY()),plane(clipBounds.minZ()));glUniform3f(clipMax,plane(clipBounds.maxX()),plane(clipBounds.maxY()),plane(clipBounds.maxZ())); }
                glBindVertexArray(scene.allocation.vaos[0]);
                int first=-1,count=0;
                for(int batch=0;batch<=surface.batchCount();batch++) {
                    boolean show=batch<surface.batchCount()&&culling.batch(batch);
                    if(show) { if(first<0)first=surface.batchFirst(batch);count+=surface.batchSize(batch); }
                    else {
                        if(first>=0) { GlNoiseUpload.bindInstances(scene.allocation,first);glDrawArraysInstanced(GL_TRIANGLES,0,6,count);calls++;submitted+=count;first=-1;count=0; }
                    }
                }
            }device.check("complete scalar draw");
        }
        return new Stats(calls,submitted,(long)submitted*2,surface.batchCount(),culling.visible,culling.culled,culling.culledFaces,culling.boundsTests,culling.testedBatches);
    }
    @Override public void close() { device.retire(allocation); }
}
