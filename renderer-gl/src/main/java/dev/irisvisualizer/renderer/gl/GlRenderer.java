package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.TexturePreparation;
import dev.irisvisualizer.renderer.WorldClip;
import dev.irisvisualizer.renderer.WorldMesh;
import dev.irisvisualizer.renderer.WorldDrawOrder;
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
        layout(location=4) in vec3 vertexTint;
        layout(location=5) in vec3 vertexLighting;
        layout(location=6) in uint shadingFlags;
        uniform bool nativeLighting;
        uniform mat4 viewProjection;
        uniform vec3 sectionOffset;
        out vec2 spriteUv;
        out vec3 worldPosition;
        out vec3 lightRgb;
        out vec3 tintRgb;
        void main() {
            worldPosition=position+sectionOffset;
            gl_Position=viewProjection*vec4(worldPosition,1.0);
            spriteUv=uv;
            tintRgb=vertexTint;
            const vec3 directions[6]=vec3[6](vec3(0,-1,0),vec3(0,1,0),vec3(0,0,-1),vec3(0,0,1),vec3(-1,0,0),vec3(1,0,0));
            uint shade=shadingFlags&7u;
            vec3 lightNormal=shade==0u?normal:directions[int(shade)-1];
            float simple=0.55+0.45*max(dot(normalize(lightNormal),normalize(vec3(0.3,1.0,0.2))),0.0);
            float emission=float(shadingFlags>>3u)/15.0;
            lightRgb=nativeLighting?vertexLighting:vec3(max(simple,emission));
        }
        """;
    private static final String GEOMETRY_FRAGMENT="""
        #version 330 core
        uniform sampler2D atlas;
        uniform int passMode;
        uniform bool clipEnabled;
        uniform vec3 clipMin;
        uniform vec3 clipMax;
        in vec3 worldPosition;
        in vec2 spriteUv;
        in vec3 lightRgb;
        in vec3 tintRgb;
        out vec4 color;
        void main() {
            if(clipEnabled && (any(lessThan(worldPosition,clipMin)) || any(greaterThan(worldPosition,clipMax)))) discard;
            vec4 sampleColor=texture(atlas,spriteUv);
            if(passMode==1 && sampleColor.a<%s) discard;
            if(passMode==2 && sampleColor.a<%s) discard;
            color=vec4(sampleColor.rgb*lightRgb*tintRgb,passMode==2?sampleColor.a:1.0);
        }
        """.formatted(TexturePreparation.AlphaClass.CUTOUT.alphaCutoff(),TexturePreparation.AlphaClass.TRANSLUCENT.alphaCutoff());
    private static final String MARKER_VERTEX="""
        #version 330 core
        layout(location=0) in vec3 corner;
        layout(location=3) in vec3 cell;
        uniform mat4 viewProjection;
        uniform vec3 sectionOffset;
        out vec3 worldPosition;
        void main() { worldPosition=corner+cell+sectionOffset;gl_Position=viewProjection*vec4(worldPosition,1.0); }
        """;
    private static final String MARKER_FRAGMENT="""
        #version 330 core
        out vec4 color;
        uniform bool clipEnabled;
        uniform vec3 clipMin;
        uniform vec3 clipMax;
        in vec3 worldPosition;
        void main() {
            if(clipEnabled && (any(lessThan(worldPosition,clipMin)) || any(greaterThan(worldPosition,clipMax)))) discard;
            color=vec4(1.0,0.0,0.8,1.0);
        }
        """;
    private static final int[] EDGES={0,1,2,3,4,5,6,7,0,2,1,3,4,6,5,7,0,4,1,5,2,6,3,7,0,7,1,6,2,5,3,4};
    private record ClipUniforms(int enabled,int minimum,int maximum) {
        static ClipUniforms read(int program) { return new ClipUniforms(uniform(program,"clipEnabled"),uniform(program,"clipMin"),uniform(program,"clipMax")); }
        // Supported camera offsets are <=2^20 and local vertices stay within bounded section/prototype space.
        // Saturating farther planes at +/-2^21 preserves membership of every renderable point and avoids infinities.
        private static float plane(double value) { return (float)Math.clamp(value,-2097152,2097152); }
        void set(boolean active,WorldClip.Bounds bounds) {
            glUniform1i(enabled,active?1:0);
            if(active) { glUniform3f(minimum,plane(bounds.minX()),plane(bounds.minY()),plane(bounds.minZ()));glUniform3f(maximum,plane(bounds.maxX()),plane(bounds.maxY()),plane(bounds.maxZ())); }
        }
    }
    private final ClipUniforms geometryClip,markerClip;
    final GlDevice device;final GlDevice.Allocation allocation;
    private final int geometryMatrix,geometryOffset,geometryPass,geometryAtlas,geometryLighting,markerMatrix,markerOffset;
    private GlRenderer(GlDevice device,GlDevice.Allocation allocation) {
        this.device=device;this.allocation=allocation;geometryClip=ClipUniforms.read(allocation.programs[0]);markerClip=ClipUniforms.read(allocation.programs[1]);geometryMatrix=uniform(allocation.programs[0],"viewProjection");geometryOffset=uniform(allocation.programs[0],"sectionOffset");geometryPass=uniform(allocation.programs[0],"passMode");geometryAtlas=uniform(allocation.programs[0],"atlas");geometryLighting=uniform(allocation.programs[0],"nativeLighting");markerMatrix=uniform(allocation.programs[1],"viewProjection");markerOffset=uniform(allocation.programs[1],"sectionOffset");
    }
    public static GlRenderer create(GlDevice device) {
        Objects.requireNonNull(device);var allocation=device.reserve(224,0,2,0,0,0,0,2,4,null);
        try(var state=new GlTransferState(device)) {
            program(device,allocation,0,GEOMETRY_VERTEX,GEOMETRY_FRAGMENT);program(device,allocation,1,MARKER_VERTEX,MARKER_FRAGMENT);
            allocation.buffers[0]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[0]);float[] corners=new float[24];for(int i=0;i<8;i++) { corners[i*3]=i&1;corners[i*3+1]=(i>>>1)&1;corners[i*3+2]=(i>>>2)&1; }
            GlScene.floats(device,GL_ARRAY_BUFFER,FloatBuffer.wrap(corners),()->false);
            allocation.buffers[1]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[1]);GlScene.integers(device,GL_ARRAY_BUFFER,IntBuffer.wrap(EDGES),()->false);
            var renderer=new GlRenderer(device,allocation);device.check("renderer initialization");return renderer;
        }catch(RuntimeException|Error failure) { try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
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
    public record FrameStats(int totalSections,int emptySections,int visibleSections,int culledSections,int solidDrawCalls,int translucentDrawCalls,int translucentFaces,int markerDrawCalls,int markerInstances,long triangleIndices,int solidRangeTests,int culledSolidRanges,int culledSolidFaces,int faceBatchTests,int culledFaceBatches,int culledTranslucentFaces,int faceBoundsTests,int markerBatchTests,int culledMarkerBatches,int culledMarkerInstances,int markerBoundsTests) {
        public FrameStats(int totalSections,int emptySections,int visibleSections,int culledSections,int solidDrawCalls,int translucentDrawCalls,int translucentFaces,int markerDrawCalls,int markerInstances,long triangleIndices) { this(totalSections,emptySections,visibleSections,culledSections,solidDrawCalls,translucentDrawCalls,translucentFaces,markerDrawCalls,markerInstances,triangleIndices,0,0,0); }
        public FrameStats(int totalSections,int emptySections,int visibleSections,int culledSections,int solidDrawCalls,int translucentDrawCalls,int translucentFaces,int markerDrawCalls,int markerInstances,long triangleIndices,int solidRangeTests,int culledSolidRanges,int culledSolidFaces) { this(totalSections,emptySections,visibleSections,culledSections,solidDrawCalls,translucentDrawCalls,translucentFaces,markerDrawCalls,markerInstances,triangleIndices,solidRangeTests,culledSolidRanges,culledSolidFaces,0,0,0); }
        public FrameStats(int totalSections,int emptySections,int visibleSections,int culledSections,int solidDrawCalls,int translucentDrawCalls,int translucentFaces,int markerDrawCalls,int markerInstances,long triangleIndices,int solidRangeTests,int culledSolidRanges,int culledSolidFaces,int faceBatchTests,int culledFaceBatches,int culledTranslucentFaces) { this(totalSections,emptySections,visibleSections,culledSections,solidDrawCalls,translucentDrawCalls,translucentFaces,markerDrawCalls,markerInstances,triangleIndices,solidRangeTests,culledSolidRanges,culledSolidFaces,faceBatchTests,culledFaceBatches,culledTranslucentFaces,0);if(culledFaceBatches>faceBatchTests)throw new IllegalArgumentException("Invalid legacy frame group counts"); }
        public FrameStats(int totalSections,int emptySections,int visibleSections,int culledSections,int solidDrawCalls,int translucentDrawCalls,int translucentFaces,int markerDrawCalls,int markerInstances,long triangleIndices,int solidRangeTests,int culledSolidRanges,int culledSolidFaces,int faceBatchTests,int culledFaceBatches,int culledTranslucentFaces,int faceBoundsTests) { this(totalSections,emptySections,visibleSections,culledSections,solidDrawCalls,translucentDrawCalls,translucentFaces,markerDrawCalls,markerInstances,triangleIndices,solidRangeTests,culledSolidRanges,culledSolidFaces,faceBatchTests,culledFaceBatches,culledTranslucentFaces,faceBoundsTests,0,0,0,0); }
        public FrameStats {
            if(totalSections<0||emptySections<0||visibleSections<0||culledSections<0||(long)emptySections+visibleSections+culledSections!=totalSections||solidDrawCalls<0||translucentDrawCalls<0||translucentFaces<0||markerDrawCalls<0||markerInstances<0||triangleIndices<0||solidRangeTests<0||culledSolidRanges<0||culledSolidRanges>solidRangeTests||culledSolidFaces<0||faceBatchTests<0||culledFaceBatches<0||culledTranslucentFaces<0||faceBoundsTests<0||faceBoundsTests>0&&faceBoundsTests<faceBatchTests||markerBatchTests<0||culledMarkerBatches<0||culledMarkerInstances<0||markerBoundsTests<markerBatchTests)throw new IllegalArgumentException("Invalid frame submission counts");
        }
    }
    /** Metadata-only temporary CPU admission size; excludes retained scene/target and host-state bookkeeping. */
    public long drawScratchBytes(GlScene scene,boolean rangeCulling) {
        return drawScratchBytes(scene,GlCullingMode.from(rangeCulling));
    }
    public long drawScratchBytes(GlScene scene,GlCullingMode mode) {
        Objects.requireNonNull(scene);Objects.requireNonNull(mode);device.live(allocation);if(scene.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Draw scratch source belongs to another device");return GlWorldCulling.workingBytes(scene.cpuScene(),mode);
    }
    public FrameStats renderFrame(GlScene scene,GlTarget target,GlCamera camera) { return draw(scene,null,null,target,camera); }
    /** Null atlas/order select the base raster/no order; translucent scenes still require an order. */
    public FrameStats renderFrame(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera) { return draw(scene,atlas,plan,target,camera); }
    public FrameStats renderFrame(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera,WorldClip clip) { return draw(scene,atlas,plan,target,camera,clip); }
    /** Disable face/marker group rejection for differential pixel/submission comparisons. */
    public FrameStats renderFrame(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera,WorldClip clip,boolean rangeCulling) { return draw(scene,atlas,plan,target,camera,clip,rangeCulling); }
    /** Explicit hierarchy, independent lazy leaf comparison, or no face-group rejection. */
    public FrameStats renderFrame(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera,WorldClip clip,GlCullingMode mode) { return draw(scene,atlas,plan,target,camera,clip,mode); }
    private FrameStats draw(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera) { return draw(scene,atlas,plan,target,camera,WorldClip.NONE); }
    private FrameStats draw(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera,WorldClip clip) {
        return draw(scene,atlas,plan,target,camera,clip,true);
    }
    private FrameStats draw(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera,WorldClip clip,boolean rangeCulling) {
        return draw(scene,atlas,plan,target,camera,clip,GlCullingMode.from(rangeCulling));
    }
    private FrameStats draw(GlScene scene,GlAtlasFrame atlas,GlDrawPlan plan,GlTarget target,GlCamera camera,WorldClip clip,GlCullingMode mode) {
        Objects.requireNonNull(mode);Objects.requireNonNull(clip);Objects.requireNonNull(scene);Objects.requireNonNull(target);Objects.requireNonNull(camera);device.live(allocation);
        if(scene.device!=device||target.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Scene, target and renderer must share one device");scene.live();device.live(target.allocation);scene.cpuScene();device.collect();
        if(plan!=null&&plan.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Face order and renderer must share one device");
        if(scene.translucentFaces>0&&plan==null)throw new GlFailure(GlFailure.Code.MATERIAL,"Prepare a complete global translucent order for this scene and camera before drawing");
        var order=plan==null?null:plan.order(scene,camera);
        int[] textures=atlas==null?scene.allocation.textures:atlas.textures(scene);
        var scratch=device.reserve(0,GlWorldCulling.workingBytes(scene.cpuScene(),mode),0,0,0,0,0,0,0,null);Throwable escaping=null;FrameStats result;
        try { result=drawAdmitted(scene,target,camera,clip,mode,order,textures); }
        catch(RuntimeException|Error failure) { escaping=failure;throw failure; }
        finally { try { device.retire(scratch); }catch(RuntimeException|Error cleanup) { if(escaping==null)throw cleanup;if(cleanup!=escaping)escaping.addSuppressed(cleanup); } }
        target.complete=true;return result;
    }
    /** This stack drops all temporary array owners before its covering admission is released. */
    private FrameStats drawAdmitted(GlScene scene,GlTarget target,GlCamera camera,WorldClip clip,GlCullingMode mode,WorldDrawOrder order,int[] textures) {
        var culling=new GlWorldCulling(scene.cpuScene(),camera,(double)target.width()/target.height(),clip,mode);var matrix=culling.matrix;var offsets=culling.offsets;var visible=culling.sections;var clipBounds=culling.clip;
        int solidCalls=0,translucentCalls=0,translucentFaces=0,markerCalls=0,markers=0,rangeTests=0,culledRanges=0,culledFaces=0,culledTranslucent=0,culledMarkers=0;long indices=0;var meshes=scene.cpuScene().sections();
        try(var state=new GlState(device)) {
            state.drawing();target.complete=false;glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target.allocation.framebuffers[0]);glViewport(0,0,target.width(),target.height());glClearBufferfv(GL_COLOR,0,new float[]{0.08f,0.1f,0.14f,1});glClearBufferfv(GL_DEPTH,0,new float[]{1});
            glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);glUseProgram(allocation.programs[0]);glUniformMatrix4fv(geometryMatrix,false,matrix);glUniform1i(geometryAtlas,0);glUniform1i(geometryLighting,scene.nativeLighting?1:0);geometryClip.set(clip.enabled(),clipBounds);
            for(int i=0;i<scene.sections.size();i++) {
                var section=scene.sections.get(i);if(!visible[i]||section.geometryVao()==0)continue;glUniform3f(geometryOffset,offsets[i*3],offsets[i*3+1],offsets[i*3+2]);glBindVertexArray(section.geometryVao());
                var mesh=meshes.get(i);
                for(var range:section.ranges()) {
                    if(range.alpha()==TexturePreparation.AlphaClass.TRANSLUCENT)continue;glBindTexture(GL_TEXTURE_2D,textures[range.page()]);glUniform1i(geometryPass,range.alpha()==TexturePreparation.AlphaClass.CUTOUT?1:0);
                    if(!mode.enabled()) { glDrawElements(GL_TRIANGLES,range.indexCount(),GL_UNSIGNED_INT,(long)range.firstIndex()*4);solidCalls++;indices+=range.indexCount();continue; }
                    int end=(range.firstIndex()+range.indexCount())/6,run=-1;
                    for(int face=range.firstIndex()/6;face<end;) {
                        int batch=face/WorldMesh.BATCH_FACES,stop=Math.min(end,(batch+1)*WorldMesh.BATCH_FACES);rangeTests++;
                        if(culling.face(i,face)) { if(run<0)run=face; }
                        else {
                            culledRanges++;culledFaces+=stop-face;
                            if(run>=0) { int count=(face-run)*6;glDrawElements(GL_TRIANGLES,count,GL_UNSIGNED_INT,(long)run*24);solidCalls++;indices+=count;run=-1; }
                        }
                        face=stop;
                    }
                    // Keep adjacent surviving groups in one original material draw, without index copies.
                    if(run>=0) { int count=(end-run)*6;glDrawElements(GL_TRIANGLES,count,GL_UNSIGNED_INT,(long)run*24);solidCalls++;indices+=count; }
                }
            }
            if(order!=null&&order.size()>0) {
                state.translucent();glUniform1i(geometryPass,2);int boundSection=-1,boundPage=-1;
                for(int at=0;at<order.size();) {
                    int index=order.section(at),face=order.face(at);if(!visible[index]) { at++;continue; }if(!culling.face(index,face)) { culledTranslucent++;at++;continue; }var section=scene.sections.get(index);var faces=meshes.get(index).faces();int page=faces.get(face).source().page(),count=1;
                    // Merge only consecutive source indices already adjacent in the sorted order.
                    while(at+count<order.size()&&order.section(at+count)==index&&order.face(at+count)==face+count&&faces.get(face+count).source().page()==page&&culling.face(index,face+count))count++;
                    if(boundSection!=index) { glUniform3f(geometryOffset,offsets[index*3],offsets[index*3+1],offsets[index*3+2]);glBindVertexArray(section.geometryVao());boundSection=index; }
                    if(boundPage!=page) { glBindTexture(GL_TEXTURE_2D,textures[page]);boundPage=page; }
                    glDrawElements(GL_TRIANGLES,count*6,GL_UNSIGNED_INT,(long)face*24);translucentCalls++;translucentFaces+=count;indices+=(long)count*6;at+=count;
                }
            }
            state.opaque();glUseProgram(allocation.programs[1]);glUniformMatrix4fv(markerMatrix,false,matrix);markerClip.set(clip.enabled(),clipBounds);
            for(int i=0;i<scene.sections.size();i++) {
                var section=scene.sections.get(i);if(!visible[i]||section.markerVao()==0)continue;
                glUniform3f(markerOffset,offsets[i*3],offsets[i*3+1],offsets[i*3+2]);glBindVertexArray(section.markerVao());glBindBuffer(GL_ARRAY_BUFFER,section.markerBuffer());
                int run=-1,end=section.markerCount();
                for(int marker=0;marker<end;) {
                    int stop=mode.enabled()?Math.min(end,(marker/WorldMesh.BATCH_FACES+1)*WorldMesh.BATCH_FACES):end;
                    if(culling.marker(i,marker)) { if(run<0)run=marker; }
                    else { culledMarkers+=stop-marker;if(run>=0) { markerRun(run,marker-run);markerCalls++;markers+=marker-run;run=-1; } }
                    marker=stop;
                }
                if(run>=0) { markerRun(run,end-run);markerCalls++;markers+=end-run; }
            }
            device.check("complete offscreen scene draw");
        }
        return new FrameStats(scene.sections.size(),culling.empty,culling.visible,culling.culled,solidCalls,translucentCalls,translucentFaces,markerCalls,markers,indices,rangeTests,culledRanges,culledFaces,culling.tests(),culling.rejected(),culledTranslucent,culling.boundsTests(),culling.markerTests(),culling.markerRejected(),culledMarkers,culling.markerBoundsTests());
    }
    /** GL 3.3 instance ranges use this owned VAO's attribute pointer, without base-instance extensions. */
    private static void markerRun(int first,int count) { glVertexAttribPointer(3,3,GL_FLOAT,false,12,(long)first*12);glDrawElementsInstanced(GL_LINES,EDGES.length,GL_UNSIGNED_INT,0L,count); }
    @Override public void close() { device.retire(allocation); }
}
