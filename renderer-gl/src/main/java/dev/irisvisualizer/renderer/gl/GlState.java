package dev.irisvisualizer.renderer.gl;

import static org.lwjgl.opengl.GL33C.*;
import org.lwjgl.opengl.GL41C;

/** Bounded snapshot of the core state changed by this backend; no host objects are edited. */
final class GlState implements AutoCloseable {
    private static final int[] FLAGS={GL_DEPTH_TEST,GL_CULL_FACE,GL_STENCIL_TEST,GL_FRAMEBUFFER_SRGB,GL_RASTERIZER_DISCARD,GL_DITHER,GL_MULTISAMPLE,GL_SAMPLE_ALPHA_TO_COVERAGE,GL_POLYGON_OFFSET_FILL,GL_COLOR_LOGIC_OP,GL_PRIMITIVE_RESTART,GL_DEPTH_CLAMP,GL_LINE_SMOOTH};
    private final GlDevice device;private final boolean[] enabled=new boolean[FLAGS.length];
    private final int active,texture,sampler,vao,array,unpack,alignment,rowLength,skipRows,skipPixels,draw,read,renderbuffer,program,depthFunction,cull,front,polygon;
    private final int[] viewport=new int[4],scissor=new int[4],colorMask=new int[4];private final double[] depthRange=new double[2];private final float lineWidth;private final boolean depthMask;
    private final boolean[] clips;
    private final boolean blend;private final float[][] viewports;private final int[][] scissors;private final double[][] depths;private final boolean[] scissorEnabled;
    GlState(GlDevice device) {
        this.device=device;device.current();device.check("incoming host state");
        // Query unit zero by indexed sampler lookup; active texture is restored even if capture fails.
        active=glGetInteger(GL_ACTIVE_TEXTURE);int savedTexture;
        try { glActiveTexture(GL_TEXTURE0);savedTexture=glGetInteger(GL_TEXTURE_BINDING_2D); }finally { glActiveTexture(active); }
        texture=savedTexture;sampler=glGetIntegeri(GL_SAMPLER_BINDING,0);vao=glGetInteger(GL_VERTEX_ARRAY_BINDING);array=glGetInteger(GL_ARRAY_BUFFER_BINDING);unpack=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        alignment=glGetInteger(GL_UNPACK_ALIGNMENT);rowLength=glGetInteger(GL_UNPACK_ROW_LENGTH);skipRows=glGetInteger(GL_UNPACK_SKIP_ROWS);skipPixels=glGetInteger(GL_UNPACK_SKIP_PIXELS);
        draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);renderbuffer=glGetInteger(GL_RENDERBUFFER_BINDING);program=glGetInteger(GL_CURRENT_PROGRAM);
        depthMask=glGetBoolean(GL_DEPTH_WRITEMASK);depthFunction=glGetInteger(GL_DEPTH_FUNC);cull=glGetInteger(GL_CULL_FACE_MODE);front=glGetInteger(GL_FRONT_FACE);int[] modes=new int[2];glGetIntegerv(GL_POLYGON_MODE,modes);polygon=modes[0];lineWidth=glGetFloat(GL_LINE_WIDTH);
        glGetIntegerv(GL_VIEWPORT,viewport);glGetIntegerv(GL_SCISSOR_BOX,scissor);var mask=device.staging();mask.limit(4);glGetBooleani_v(GL_COLOR_WRITEMASK,0,mask);for(int i=0;i<4;i++)colorMask[i]=mask.get(i)==0?0:1;glGetDoublev(GL_DEPTH_RANGE,depthRange);blend=glIsEnabledi(GL_BLEND,0);
        int views=device.indexedViewports()?glGetInteger(GL41C.GL_MAX_VIEWPORTS):1;if(views<1||views>32)throw new GlFailure(GlFailure.Code.CAPABILITY,"Viewport state exceeds the backend snapshot bound");
        viewports=new float[views][4];scissors=new int[views][4];depths=new double[views][2];scissorEnabled=new boolean[views];
        for(int i=0;i<views;i++) {
            if(device.indexedViewports()) { GL41C.glGetFloati_v(GL_VIEWPORT,i,viewports[i]);glGetIntegeri_v(GL_SCISSOR_BOX,i,scissors[i]);GL41C.glGetDoublei_v(GL_DEPTH_RANGE,i,depths[i]);scissorEnabled[i]=glIsEnabledi(GL_SCISSOR_TEST,i); }
            else scissorEnabled[i]=glIsEnabled(GL_SCISSOR_TEST);
        }
        int clipCount=glGetInteger(GL_MAX_CLIP_DISTANCES);if(clipCount<0||clipCount>32)throw new GlFailure(GlFailure.Code.CAPABILITY,"Clip-distance state exceeds the backend snapshot bound");clips=new boolean[clipCount];
        for(int i=0;i<clips.length;i++)clips[i]=glIsEnabled(GL_CLIP_DISTANCE0+i);for(int i=0;i<FLAGS.length;i++)enabled[i]=glIsEnabled(FLAGS[i]);device.check("host state capture");
    }
    static void unpack() { glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glPixelStorei(GL_UNPACK_ALIGNMENT,1);glPixelStorei(GL_UNPACK_ROW_LENGTH,0);glPixelStorei(GL_UNPACK_SKIP_ROWS,0);glPixelStorei(GL_UNPACK_SKIP_PIXELS,0); }
    void drawing() {
        for(int i=0;i<clips.length;i++)glDisable(GL_CLIP_DISTANCE0+i);for(int flag:FLAGS)glDisable(flag);glDisablei(GL_BLEND,0);glDisable(GL_SCISSOR_TEST);glEnable(GL_DEPTH_TEST);glEnable(GL_CULL_FACE);glCullFace(GL_BACK);glFrontFace(GL_CCW);glDepthFunc(GL_LEQUAL);glDepthMask(true);glDepthRange(0,1);glColorMaski(0,true,true,true,true);glPolygonMode(GL_FRONT_AND_BACK,GL_FILL);glLineWidth(1);
    }
    @Override public void close() {
        device.current();glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindRenderbuffer(GL_RENDERBUFFER,renderbuffer);glUseProgram(program);
        glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,array);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);glPixelStorei(GL_UNPACK_ALIGNMENT,alignment);glPixelStorei(GL_UNPACK_ROW_LENGTH,rowLength);glPixelStorei(GL_UNPACK_SKIP_ROWS,skipRows);glPixelStorei(GL_UNPACK_SKIP_PIXELS,skipPixels);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(0,sampler);glActiveTexture(active);
        if(device.indexedViewports()) {
            for(int i=0;i<viewports.length;i++) { var v=viewports[i];var s=scissors[i];var d=depths[i];GL41C.glViewportIndexedf(i,v[0],v[1],v[2],v[3]);GL41C.glScissorIndexed(i,s[0],s[1],s[2],s[3]);GL41C.glDepthRangeIndexed(i,d[0],d[1]);if(scissorEnabled[i])glEnablei(GL_SCISSOR_TEST,i);else glDisablei(GL_SCISSOR_TEST,i); }
        } else { glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glScissor(scissor[0],scissor[1],scissor[2],scissor[3]);glDepthRange(depthRange[0],depthRange[1]);if(scissorEnabled[0])glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST); }
        if(blend)glEnablei(GL_BLEND,0);else glDisablei(GL_BLEND,0);glColorMaski(0,colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0);glDepthMask(depthMask);glDepthFunc(depthFunction);glCullFace(cull);glFrontFace(front);glPolygonMode(GL_FRONT_AND_BACK,polygon);glLineWidth(lineWidth);
        for(int i=0;i<clips.length;i++)if(clips[i])glEnable(GL_CLIP_DISTANCE0+i);else glDisable(GL_CLIP_DISTANCE0+i);for(int i=0;i<FLAGS.length;i++)if(enabled[i])glEnable(FLAGS[i]);else glDisable(FLAGS[i]);device.check("host state restoration");
    }
}
