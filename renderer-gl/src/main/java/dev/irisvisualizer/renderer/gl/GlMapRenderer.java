package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.MapView;
import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Top-left map canvas rendered into a private offscreen target, preserving source RGBA. */
public final class GlMapRenderer implements AutoCloseable {
    private static final String VERTEX="""
        #version 330 core
        uniform vec4 clipRect;
        uniform vec4 uvRect;
        out vec2 imageUv;
        void main() {
            vec2 corner=vec2(float(gl_VertexID/2),float(gl_VertexID%2));
            gl_Position=vec4(mix(clipRect.xy,clipRect.zw,corner),0.0,1.0);
            imageUv=mix(uvRect.xy,uvRect.zw,corner);
        }
        """;
    private static final String FRAGMENT="""
        #version 330 core
        uniform sampler2D image;
        in vec2 imageUv;
        out vec4 color;
        void main() { color=texture(image,imageUv); }
        """;
    final GlDevice device;final GlDevice.Allocation allocation;private final int clipRect,uvRect,image;
    private GlMapRenderer(GlDevice device,GlDevice.Allocation allocation) { this.device=device;this.allocation=allocation;clipRect=uniform("clipRect");uvRect=uniform("uvRect");image=uniform("image"); }
    private int uniform(String name) { int location=glGetUniformLocation(allocation.programs[0],name);if(location<0)throw new GlFailure(GlFailure.Code.SHADER,"Required map uniform is absent: "+name);return location; }
    public static GlMapRenderer create(GlDevice device) {
        Objects.requireNonNull(device);var allocation=device.reserve(0,0,0,0,1,0,0,1,2,null);
        try(var state=new GlState(device)) {
            shader(allocation,0,GL_VERTEX_SHADER,VERTEX);shader(allocation,1,GL_FRAGMENT_SHADER,FRAGMENT);allocation.programs[0]=glCreateProgram();
            for(int shader:allocation.shaders)glAttachShader(allocation.programs[0],shader);glLinkProgram(allocation.programs[0]);
            if(glGetProgrami(allocation.programs[0],GL_LINK_STATUS)==GL_FALSE)throw new GlFailure(GlFailure.Code.SHADER,"Map program linking failed: "+glGetProgramInfoLog(allocation.programs[0],4096));
            allocation.vaos[0]=glGenVertexArrays();var renderer=new GlMapRenderer(device,allocation);device.check("map renderer initialization");return renderer;
        }catch(RuntimeException|Error failure) { try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    private static void shader(GlDevice.Allocation allocation,int index,int type,String source) {
        int shader=glCreateShader(type);allocation.shaders[index]=shader;glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE)throw new GlFailure(GlFailure.Code.SHADER,"Map shader compilation failed: "+glGetShaderInfoLog(shader,4096));
    }
    /** Inputs are borrowed. Restoration must succeed before target.frameComplete becomes true. */
    public void render(GlMap map,GlTarget target,MapView view) {
        Objects.requireNonNull(map);Objects.requireNonNull(target);Objects.requireNonNull(view);device.live(allocation);
        if(map.device!=device||target.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Map, target and renderer must share their original device");
        int texture=map.textureId();device.live(target.allocation);if(view.width()!=map.width()||view.height()!=map.height())throw new GlFailure(GlFailure.Code.STALE,"Map transform dimensions differ from its exact uploaded image");
        double left=Math.max(0,view.left()),top=Math.max(0,view.top()),right=Math.min(target.width(),view.right()),bottom=Math.min(target.height(),view.bottom());
        target.complete=false;
        try(var state=new GlState(device)) {
            state.drawing();glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_CULL_FACE);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target.allocation.framebuffers[0]);glViewport(0,0,target.width(),target.height());glClearBufferfv(GL_COLOR,0,new float[]{0,0,0,0});
            if(left<right&&top<bottom) {
                double spanX=view.width()*view.pixelsPerSample(),spanY=view.height()*view.pixelsPerSample();
                glUseProgram(allocation.programs[0]);glBindVertexArray(allocation.vaos[0]);glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);glBindTexture(GL_TEXTURE_2D,texture);glUniform1i(image,0);
                glUniform4f(clipRect,(float)(2*left/target.width()-1),(float)(1-2*top/target.height()),(float)(2*right/target.width()-1),(float)(1-2*bottom/target.height()));
                glUniform4f(uvRect,(float)((left-view.left())/spanX),(float)((top-view.top())/spanY),(float)((right-view.left())/spanX),(float)((bottom-view.top())/spanY));glDrawArrays(GL_TRIANGLE_STRIP,0,4);
            }
            device.check("complete offscreen map draw");
        }
        target.complete=true;
    }
    @Override public void close() { device.retire(allocation); }
}
