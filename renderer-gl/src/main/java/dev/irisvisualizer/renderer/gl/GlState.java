package dev.irisvisualizer.renderer.gl;

import static org.lwjgl.opengl.GL33C.*;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.ARBClipControl;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.EXTDepthBoundsTest;

/** Bounded snapshot of the core state changed by this backend; no host objects are edited. */
final class GlState implements AutoCloseable {
    private static final int[] FLAGS={GL_DEPTH_TEST,GL_CULL_FACE,GL_STENCIL_TEST,GL_FRAMEBUFFER_SRGB,GL_RASTERIZER_DISCARD,GL_DITHER,GL_MULTISAMPLE,GL_SAMPLE_ALPHA_TO_COVERAGE,GL_SAMPLE_ALPHA_TO_ONE,GL_SAMPLE_COVERAGE,GL_SAMPLE_MASK,GL_POLYGON_OFFSET_FILL,GL_POLYGON_OFFSET_LINE,GL_POLYGON_OFFSET_POINT,GL_POLYGON_SMOOTH,GL_COLOR_LOGIC_OP,GL_PRIMITIVE_RESTART,GL_DEPTH_CLAMP,GL_LINE_SMOOTH};
    private final GlDevice device;private final boolean[] enabled=new boolean[FLAGS.length];
    private final GlTransferState transfer;
    private final int program,depthFunction,cull,front,polygon,clipOrigin,clipDepth;
    private final boolean depthBounds,fixedRestart,sampleShading;
    private final int[] viewport=new int[4],scissor=new int[4],colorMask=new int[4];private final double[] depthRange=new double[2];private final float lineWidth;private final boolean depthMask;
    private final boolean[] clips;
    private final boolean blend;private final float[][] viewports;private final int[][] scissors;private final double[][] depths;private final boolean[] scissorEnabled;
    private final int blendSrcRgb,blendDstRgb,blendSrcAlpha,blendDstAlpha,blendEquationRgb,blendEquationAlpha;
    GlState(GlDevice device) {
        this.device=device;device.current();device.check("incoming host state");device.drawingAllowed();transfer=new GlTransferState(device);program=glGetInteger(GL_CURRENT_PROGRAM);
        clipOrigin=device.clipControl()?glGetInteger(ARBClipControl.GL_CLIP_ORIGIN):ARBClipControl.GL_LOWER_LEFT;clipDepth=device.clipControl()?glGetInteger(ARBClipControl.GL_CLIP_DEPTH_MODE):ARBClipControl.GL_NEGATIVE_ONE_TO_ONE;
        depthBounds=device.depthBounds()&&glIsEnabled(EXTDepthBoundsTest.GL_DEPTH_BOUNDS_TEST_EXT);fixedRestart=device.fixedRestart()&&glIsEnabled(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX);sampleShading=device.sampleShading()&&glIsEnabled(GL40C.GL_SAMPLE_SHADING);
        depthMask=glGetBoolean(GL_DEPTH_WRITEMASK);depthFunction=glGetInteger(GL_DEPTH_FUNC);cull=glGetInteger(GL_CULL_FACE_MODE);front=glGetInteger(GL_FRONT_FACE);int[] modes=new int[2];glGetIntegerv(GL_POLYGON_MODE,modes);polygon=modes[0];lineWidth=glGetFloat(GL_LINE_WIDTH);
        glGetIntegerv(GL_VIEWPORT,viewport);glGetIntegerv(GL_SCISSOR_BOX,scissor);var mask=device.staging();mask.limit(4);glGetBooleani_v(GL_COLOR_WRITEMASK,0,mask);for(int i=0;i<4;i++)colorMask[i]=mask.get(i)==0?0:1;glGetDoublev(GL_DEPTH_RANGE,depthRange);blend=glIsEnabledi(GL_BLEND,0);
        blendSrcRgb=blendValue(GL_BLEND_SRC_RGB);blendDstRgb=blendValue(GL_BLEND_DST_RGB);blendSrcAlpha=blendValue(GL_BLEND_SRC_ALPHA);blendDstAlpha=blendValue(GL_BLEND_DST_ALPHA);blendEquationRgb=blendValue(GL_BLEND_EQUATION_RGB);blendEquationAlpha=blendValue(GL_BLEND_EQUATION_ALPHA);
        int views=device.indexedViewports()?glGetInteger(GL41C.GL_MAX_VIEWPORTS):1;if(views<1||views>32)throw new GlFailure(GlFailure.Code.CAPABILITY,"Viewport state exceeds the backend snapshot bound");
        viewports=new float[views][4];scissors=new int[views][4];depths=new double[views][2];scissorEnabled=new boolean[views];
        for(int i=0;i<views;i++) {
            if(device.indexedViewports()) { GL41C.glGetFloati_v(GL_VIEWPORT,i,viewports[i]);glGetIntegeri_v(GL_SCISSOR_BOX,i,scissors[i]);GL41C.glGetDoublei_v(GL_DEPTH_RANGE,i,depths[i]);scissorEnabled[i]=glIsEnabledi(GL_SCISSOR_TEST,i); }
            else scissorEnabled[i]=glIsEnabled(GL_SCISSOR_TEST);
        }
        int clipCount=glGetInteger(GL_MAX_CLIP_DISTANCES);if(clipCount<0||clipCount>32)throw new GlFailure(GlFailure.Code.CAPABILITY,"Clip-distance state exceeds the backend snapshot bound");clips=new boolean[clipCount];
        for(int i=0;i<clips.length;i++)clips[i]=glIsEnabled(GL_CLIP_DISTANCE0+i);for(int i=0;i<FLAGS.length;i++)enabled[i]=glIsEnabled(FLAGS[i]);device.check("host state capture");
    }
    private void clipControl(int origin,int depth) { if(device.coreClipControl())GL45C.glClipControl(origin,depth);else if(device.clipControl())ARBClipControl.glClipControl(origin,depth); }
    private static void flag(int parameter,boolean enabled) { if(enabled)glEnable(parameter);else glDisable(parameter); }
    private int blendValue(int parameter) { return device.indexedBlend()?glGetIntegeri(parameter,0):glGetInteger(parameter); }
    private void blendFunctions(int srcRgb,int dstRgb,int srcAlpha,int dstAlpha,int equationRgb,int equationAlpha) {
        if(device.coreIndexedBlend()) { GL40C.glBlendFuncSeparatei(0,srcRgb,dstRgb,srcAlpha,dstAlpha);GL40C.glBlendEquationSeparatei(0,equationRgb,equationAlpha); }
        else if(device.indexedBlend()) { ARBDrawBuffersBlend.glBlendFuncSeparateiARB(0,srcRgb,dstRgb,srcAlpha,dstAlpha);ARBDrawBuffersBlend.glBlendEquationSeparateiARB(0,equationRgb,equationAlpha); }
        else { glBlendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);glBlendEquationSeparate(equationRgb,equationAlpha); }
    }
    void translucent() { blendFunctions(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA,GL_FUNC_ADD,GL_FUNC_ADD);glEnablei(GL_BLEND,0);glDepthMask(false); }
    void opaque() { glDisablei(GL_BLEND,0);glDepthMask(true); }
    void drawing() {
        clipControl(ARBClipControl.GL_LOWER_LEFT,ARBClipControl.GL_NEGATIVE_ONE_TO_ONE);
        if(device.depthBounds())glDisable(EXTDepthBoundsTest.GL_DEPTH_BOUNDS_TEST_EXT);if(device.fixedRestart())glDisable(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX);if(device.sampleShading())glDisable(GL40C.GL_SAMPLE_SHADING);
        for(int i=0;i<clips.length;i++)glDisable(GL_CLIP_DISTANCE0+i);for(int flag:FLAGS)glDisable(flag);glDisablei(GL_BLEND,0);glDisable(GL_SCISSOR_TEST);glEnable(GL_DEPTH_TEST);glEnable(GL_CULL_FACE);glCullFace(GL_BACK);glFrontFace(GL_CCW);glDepthFunc(GL_LEQUAL);glDepthMask(true);glDepthRange(0,1);glColorMaski(0,true,true,true,true);glPolygonMode(GL_FRONT_AND_BACK,GL_FILL);glLineWidth(1);
        device.check("offscreen drawing state");
    }
    @Override public void close() {
        device.current();
        // Restore transfer bindings even if a raster/program restoration call fails its GL check.
        try(var bindings=transfer) { restoreDrawing(); }
    }
    private void restoreDrawing() {
        glUseProgram(program);clipControl(clipOrigin,clipDepth);
        if(device.depthBounds())flag(EXTDepthBoundsTest.GL_DEPTH_BOUNDS_TEST_EXT,depthBounds);if(device.fixedRestart())flag(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX,fixedRestart);if(device.sampleShading())flag(GL40C.GL_SAMPLE_SHADING,sampleShading);
        if(device.indexedViewports()) {
            for(int i=0;i<viewports.length;i++) { var v=viewports[i];var s=scissors[i];var d=depths[i];GL41C.glViewportIndexedf(i,v[0],v[1],v[2],v[3]);GL41C.glScissorIndexed(i,s[0],s[1],s[2],s[3]);GL41C.glDepthRangeIndexed(i,d[0],d[1]);if(scissorEnabled[i])glEnablei(GL_SCISSOR_TEST,i);else glDisablei(GL_SCISSOR_TEST,i); }
        } else { glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glScissor(scissor[0],scissor[1],scissor[2],scissor[3]);glDepthRange(depthRange[0],depthRange[1]);if(scissorEnabled[0])glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST); }
        blendFunctions(blendSrcRgb,blendDstRgb,blendSrcAlpha,blendDstAlpha,blendEquationRgb,blendEquationAlpha);if(blend)glEnablei(GL_BLEND,0);else glDisablei(GL_BLEND,0);glColorMaski(0,colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0);glDepthMask(depthMask);glDepthFunc(depthFunction);glCullFace(cull);glFrontFace(front);glPolygonMode(GL_FRONT_AND_BACK,polygon);glLineWidth(lineWidth);
        for(int i=0;i<clips.length;i++)if(clips[i])glEnable(GL_CLIP_DISTANCE0+i);else glDisable(GL_CLIP_DISTANCE0+i);for(int i=0;i<FLAGS.length;i++)if(enabled[i])glEnable(FLAGS[i]);else glDisable(FLAGS[i]);device.check("host state restoration");
    }
}
