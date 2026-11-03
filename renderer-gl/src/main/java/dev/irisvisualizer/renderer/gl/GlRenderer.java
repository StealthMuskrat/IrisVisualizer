package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.TexturePreparation;
import java.nio.*;
import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Nearest/base-level material passes with native vertex tint and explicit global translucent order. */
public final class GlRenderer implements AutoCloseable {
    private static final String GEOMETRY_VERTEX="""
        #version 330 core
        layout(location=0) in vec3 position;
        layout(location=1) in vec3 normal;
        layout(location=2) in vec2 uv;
        layout(location=4) in vec4 vertexTint;
        layout(location=5) in vec3 vertexLighting;
        uniform bool nativeLighting;
        uniform mat4 viewProjection;
        uniform vec3 sectionOffset;
        out vec2 spriteUv;
        out vec3 lightRgb;
        out vec3 tintRgb;
        void main() {
            gl_Position=viewProjection*vec4(position+sectionOffset,1.0);
            spriteUv=uv;
            tintRgb=vertexTint.rgb;
            lightRgb=nativeLighting?vertexLighting:vec3(0.55+0.45*max(dot(normalize(normal),normalize(vec3(0.3,1.0,0.2))),0.0));
        }
        """;
    private static final String GEOMETRY_FRAGMENT="""
        #version 330 core
        uniform sampler2D atlas;
        uniform int passMode;
        in vec2 spriteUv;
        in vec3 lightRgb;
        in vec3 tintRgb;
        out vec4 color;
        void main() {
            vec4 sampleColor=texture(atlas,spriteUv);
            if(passMode==1 && sampleColor.a<0.5) discard;
            if(passMode==2 && sampleColor.a<=0.0) discard;
            color=vec4(sampleColor.rgb*lightRgb*tintRgb,passMode==2?sampleColor.a:1.0);
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
    private final int geometryMatrix,geometryOffset,geometryPass,geometryAtlas,geometryLighting,markerMatrix,markerOffset;
    private GlRenderer(GlDevice device,GlDevice.Allocation allocation) {
        this.device=device;this.allocation=allocation;geometryMatrix=uniform(allocation.programs[0],"viewProjection");geometryOffset=uniform(allocation.programs[0],"sectionOffset");geometryPass=uniform(allocation.programs[0],"passMode");geometryAtlas=uniform(allocation.programs[0],"atlas");geometryLighting=uniform(allocation.programs[0],"nativeLighting");markerMatrix=uniform(allocation.programs[1],"viewProjection");markerOffset=uniform(allocation.programs[1],"sectionOffset");
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
        draw(scene,null,null,target,camera);
    }
    public void render(GlScene scene,GlAtlasFrame atlas,GlTarget target,GlCamera camera) {
        Objects.requireNonNull(atlas);draw(scene,atlas,null,target,camera);
    }
    public void render(GlScene scene,GlDrawPlan plan,GlTarget target,GlCamera camera) {
        Objects.requireNonNull(plan);draw(scene,null,plan,target,camera);
    }
    public void render(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera) {
        Objects.requireNonNull(atlas);Objects.requireNonNull(plan);draw(scene,atlas,plan,target,camera);
    }
    /** Submission counts are returned only after a complete draw and host-state restoration. */
    public record FrameStats(int totalSections,int emptySections,int visibleSections,int culledSections,int solidDrawCalls,int translucentDrawCalls,int translucentFaces,int markerDrawCalls,int markerInstances,long triangleIndices) {
        public FrameStats {
            if(totalSections<0||emptySections<0||visibleSections<0||culledSections<0||(long)emptySections+visibleSections+culledSections!=totalSections||solidDrawCalls<0||translucentDrawCalls<0||translucentFaces<0||markerDrawCalls<0||markerInstances<0||triangleIndices<0)throw new IllegalArgumentException("Invalid frame submission counts");
        }
    }
    public FrameStats renderFrame(GlScene scene,GlTarget target,GlCamera camera) { return draw(scene,null,null,target,camera); }
    /** Null atlas/order select the base raster/no order; translucent scenes still require an order. */
    public FrameStats renderFrame(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera) { return draw(scene,atlas,plan,target,camera); }
    private FrameStats draw(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera) {
        Objects.requireNonNull(scene);Objects.requireNonNull(target);Objects.requireNonNull(camera);device.live(allocation);
        if(scene.device!=device||target.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Scene, target and renderer must share one device");device.live(scene.allocation);device.live(target.allocation);scene.cpuScene();device.collect();
        if(plan!=null&&plan.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Face order and renderer must share one device");
        if(scene.translucentFaces>0&&plan==null)throw new GlFailure(GlFailure.Code.MATERIAL,"Prepare a complete global translucent order for this scene and camera before drawing");
        var order=plan==null?null:plan.order(scene,camera);
        int[] textures=atlas==null?scene.allocation.textures:atlas.textures(scene);
        float[] matrix=camera.matrix((double)target.width()/target.height());float[] offsets=new float[scene.sections.size()*3];boolean[] visible=new boolean[scene.sections.size()];var frustum=new GlFrustum(matrix);int empty=0,shown=0,culled=0;
        for(int i=0;i<scene.sections.size();i++) {
            var section=scene.sections.get(i);camera.offset(section.origin(),offsets,i*3);
            if(section.bounds().isEmpty()) { empty++;continue; }
            visible[i]=frustum.visible(section.bounds().orElseThrow(),offsets,i*3);if(visible[i])shown++;else culled++;
        }
        int solidCalls=0,translucentCalls=0,translucentFaces=0,markerCalls=0,markers=0;long indices=0;
        target.complete=false;
        try(var state=new GlState(device)) {
            state.drawing();glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target.allocation.framebuffers[0]);glViewport(0,0,target.width(),target.height());glClearBufferfv(GL_COLOR,0,new float[]{0.08f,0.1f,0.14f,1});glClearBufferfv(GL_DEPTH,0,new float[]{1});
            glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);glUseProgram(allocation.programs[0]);glUniformMatrix4fv(geometryMatrix,false,matrix);glUniform1i(geometryAtlas,0);glUniform1i(geometryLighting,scene.nativeLighting?1:0);
            for(int i=0;i<scene.sections.size();i++) {
                var section=scene.sections.get(i);if(!visible[i]||section.geometryVao()==0)continue;glUniform3f(geometryOffset,offsets[i*3],offsets[i*3+1],offsets[i*3+2]);glBindVertexArray(section.geometryVao());
                for(var range:section.ranges()) { if(range.alpha()==TexturePreparation.AlphaClass.TRANSLUCENT)continue;glBindTexture(GL_TEXTURE_2D,textures[range.page()]);glUniform1i(geometryPass,range.alpha()==TexturePreparation.AlphaClass.CUTOUT?1:0);glDrawElements(GL_TRIANGLES,range.indexCount(),GL_UNSIGNED_INT,(long)range.firstIndex()*4);solidCalls++;indices+=range.indexCount(); }
            }
            if(order!=null&&order.size()>0) {
                state.translucent();glUniform1i(geometryPass,2);var meshes=scene.cpuScene().sections();int boundSection=-1,boundPage=-1;
                for(int at=0;at<order.size();) {
                    int index=order.section(at),face=order.face(at);if(!visible[index]) { at++;continue; }var section=scene.sections.get(index);var faces=meshes.get(index).faces();int page=faces.get(face).source().page(),count=1;
                    // Merge only consecutive source indices already adjacent in the sorted order.
                    while(at+count<order.size()&&order.section(at+count)==index&&order.face(at+count)==face+count&&faces.get(face+count).source().page()==page)count++;
                    if(boundSection!=index) { glUniform3f(geometryOffset,offsets[index*3],offsets[index*3+1],offsets[index*3+2]);glBindVertexArray(section.geometryVao());boundSection=index; }
                    if(boundPage!=page) { glBindTexture(GL_TEXTURE_2D,textures[page]);boundPage=page; }
                    glDrawElements(GL_TRIANGLES,count*6,GL_UNSIGNED_INT,(long)face*24);translucentCalls++;translucentFaces+=count;indices+=(long)count*6;at+=count;
                }
            }
            state.opaque();glUseProgram(allocation.programs[1]);glUniformMatrix4fv(markerMatrix,false,matrix);
            for(int i=0;i<scene.sections.size();i++) { var section=scene.sections.get(i);if(!visible[i]||section.markerVao()==0)continue;glUniform3f(markerOffset,offsets[i*3],offsets[i*3+1],offsets[i*3+2]);glBindVertexArray(section.markerVao());glDrawElementsInstanced(GL_LINES,EDGES.length,GL_UNSIGNED_INT,0L,section.markerCount());markerCalls++;markers+=section.markerCount(); }
            device.check("complete offscreen scene draw");
        }
        target.complete=true;return new FrameStats(scene.sections.size(),empty,shown,culled,solidCalls,translucentCalls,translucentFaces,markerCalls,markers,indices);
    }
    @Override public void close() { device.retire(allocation); }
}
