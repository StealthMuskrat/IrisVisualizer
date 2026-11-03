package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.TexturePreparation;
import java.nio.*;
import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Basic nearest/base-level opaque/cutout renderer with an instanced diagnostic marker pass. */
public final class GlRenderer implements AutoCloseable {
    private static final String GEOMETRY_VERTEX="""
        #version 330 core
        layout(location=0) in vec3 position;
        layout(location=1) in vec3 normal;
        layout(location=2) in vec2 uv;
        uniform mat4 viewProjection;
        uniform vec3 sectionOffset;
        out vec2 spriteUv;
        out float light;
        void main() {
            gl_Position=viewProjection*vec4(position+sectionOffset,1.0);
            spriteUv=uv;
            light=0.55+0.45*max(dot(normalize(normal),normalize(vec3(0.3,1.0,0.2))),0.0);
        }
        """;
    private static final String GEOMETRY_FRAGMENT="""
        #version 330 core
        uniform sampler2D atlas;
        uniform int cutout;
        in vec2 spriteUv;
        in float light;
        out vec4 color;
        void main() {
            vec4 sampleColor=texture(atlas,spriteUv);
            if(cutout!=0 && sampleColor.a<0.5) discard;
            color=vec4(sampleColor.rgb*light,1.0);
        }
        """;
    private static final String MARKER_VERTEX="""
        #version 330 core
        layout(location=0) in vec3 corner;
        layout(location=3) in vec3 cell;
        uniform mat4 viewProjection;
        uniform vec3 sectionOffset;
        void main() { gl_Position=viewProjection*vec4(corner+cell+sectionOffset,1.0); }
        """;
    private static final String MARKER_FRAGMENT="""
        #version 330 core
        out vec4 color;
        void main() { color=vec4(1.0,0.0,0.8,1.0); }
        """;
    private static final int[] EDGES={0,1,2,3,4,5,6,7,0,2,1,3,4,6,5,7,0,4,1,5,2,6,3,7,0,7,1,6,2,5,3,4};
    final GlDevice device;final GlDevice.Allocation allocation;
    private final int geometryMatrix,geometryOffset,geometryCutout,geometryAtlas,markerMatrix,markerOffset;
    private GlRenderer(GlDevice device,GlDevice.Allocation allocation) {
        this.device=device;this.allocation=allocation;geometryMatrix=uniform(allocation.programs[0],"viewProjection");geometryOffset=uniform(allocation.programs[0],"sectionOffset");geometryCutout=uniform(allocation.programs[0],"cutout");geometryAtlas=uniform(allocation.programs[0],"atlas");markerMatrix=uniform(allocation.programs[1],"viewProjection");markerOffset=uniform(allocation.programs[1],"sectionOffset");
    }
    public static GlRenderer create(GlDevice device) {
        Objects.requireNonNull(device);var allocation=device.reserve(224,0,2,0,0,0,0,2,4,null);
        try(var state=new GlState(device)) {
            program(device,allocation,0,GEOMETRY_VERTEX,GEOMETRY_FRAGMENT);program(device,allocation,1,MARKER_VERTEX,MARKER_FRAGMENT);
            allocation.buffers[0]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[0]);float[] corners=new float[24];for(int i=0;i<8;i++) { corners[i*3]=i&1;corners[i*3+1]=(i>>>1)&1;corners[i*3+2]=(i>>>2)&1; }
            GlScene.floats(device,GL_ARRAY_BUFFER,FloatBuffer.wrap(corners),()->false);
            allocation.buffers[1]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[1]);GlScene.integers(device,GL_ARRAY_BUFFER,IntBuffer.wrap(EDGES),()->false);
            var renderer=new GlRenderer(device,allocation);device.check("renderer initialization");return renderer;
        }catch(RuntimeException|Error failure) { try { device.retire(allocation); }catch(RuntimeException retirement) { failure.addSuppressed(retirement); }throw failure; }
    }
    private static int uniform(int program,String name) { int location=glGetUniformLocation(program,name);if(location<0)throw new GlFailure(GlFailure.Code.SHADER,"Required renderer uniform is absent: "+name);return location; }
    private static void program(GlDevice device,GlDevice.Allocation allocation,int index,String vertex,String fragment) {
        int vs=index*2,fs=vs+1;allocation.shaders[vs]=glCreateShader(GL_VERTEX_SHADER);glShaderSource(allocation.shaders[vs],vertex);glCompileShader(allocation.shaders[vs]);
        if(glGetShaderi(allocation.shaders[vs],GL_COMPILE_STATUS)==GL_FALSE)throw new GlFailure(GlFailure.Code.SHADER,"Vertex shader compilation failed: "+glGetShaderInfoLog(allocation.shaders[vs],4096));
        allocation.shaders[fs]=glCreateShader(GL_FRAGMENT_SHADER);glShaderSource(allocation.shaders[fs],fragment);glCompileShader(allocation.shaders[fs]);
        if(glGetShaderi(allocation.shaders[fs],GL_COMPILE_STATUS)==GL_FALSE)throw new GlFailure(GlFailure.Code.SHADER,"Fragment shader compilation failed: "+glGetShaderInfoLog(allocation.shaders[fs],4096));
        allocation.programs[index]=glCreateProgram();glAttachShader(allocation.programs[index],allocation.shaders[vs]);glAttachShader(allocation.programs[index],allocation.shaders[fs]);glLinkProgram(allocation.programs[index]);
        if(glGetProgrami(allocation.programs[index],GL_LINK_STATUS)==GL_FALSE)throw new GlFailure(GlFailure.Code.SHADER,"Renderer program linking failed: "+glGetProgramInfoLog(allocation.programs[index],4096));device.check("shader program creation");
    }
    public void render(GlScene scene,GlTarget target,GlCamera camera) {
        draw(scene,null,target,camera);
    }
    public void render(GlScene scene,GlAtlasFrame atlas,GlTarget target,GlCamera camera) {
        Objects.requireNonNull(atlas);draw(scene,atlas,target,camera);
    }
    private void draw(GlScene scene,GlAtlasFrame atlas,GlTarget target,GlCamera camera) {
        Objects.requireNonNull(scene);Objects.requireNonNull(target);Objects.requireNonNull(camera);device.live(allocation);
        if(scene.device!=device||target.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Scene, target and renderer must share one device");device.live(scene.allocation);device.live(target.allocation);scene.cpuScene();device.collect();
        int[] textures=atlas==null?scene.allocation.textures:atlas.textures(scene);
        float[] matrix=camera.matrix((double)target.width()/target.height());float[] offsets=new float[scene.sections.size()*3];for(int i=0;i<scene.sections.size();i++)camera.offset(scene.sections.get(i).origin(),offsets,i*3);
        target.complete=false;
        try(var state=new GlState(device)) {
            state.drawing();glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target.allocation.framebuffers[0]);glViewport(0,0,target.width(),target.height());glClearBufferfv(GL_COLOR,0,new float[]{0.08f,0.1f,0.14f,1});glClearBufferfv(GL_DEPTH,0,new float[]{1});
            glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);glUseProgram(allocation.programs[0]);glUniformMatrix4fv(geometryMatrix,false,matrix);glUniform1i(geometryAtlas,0);
            for(int i=0;i<scene.sections.size();i++) {
                var section=scene.sections.get(i);if(section.geometryVao()==0)continue;glUniform3f(geometryOffset,offsets[i*3],offsets[i*3+1],offsets[i*3+2]);glBindVertexArray(section.geometryVao());
                for(var range:section.ranges()) { glBindTexture(GL_TEXTURE_2D,textures[range.page()]);glUniform1i(geometryCutout,range.alpha()==TexturePreparation.AlphaClass.CUTOUT?1:0);glDrawElements(GL_TRIANGLES,range.indexCount(),GL_UNSIGNED_INT,(long)range.firstIndex()*4); }
            }
            glUseProgram(allocation.programs[1]);glUniformMatrix4fv(markerMatrix,false,matrix);
            for(int i=0;i<scene.sections.size();i++) { var section=scene.sections.get(i);if(section.markerVao()==0)continue;glUniform3f(markerOffset,offsets[i*3],offsets[i*3+1],offsets[i*3+2]);glBindVertexArray(section.markerVao());glDrawElementsInstanced(GL_LINES,EDGES.length,GL_UNSIGNED_INT,0L,section.markerCount()); }
            device.check("complete offscreen scene draw");
        }
        target.complete=true;
    }
    @Override public void close() { device.retire(allocation); }
}
