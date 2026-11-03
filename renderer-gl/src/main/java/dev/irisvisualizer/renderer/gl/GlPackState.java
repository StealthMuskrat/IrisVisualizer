package dev.irisvisualizer.renderer.gl;

import static org.lwjgl.opengl.GL33C.*;

/** Readback-only state guard; never changes host framebuffer attachments or buffer storage. */
final class GlPackState implements AutoCloseable {
    private static final int[] PARAMETERS={GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_ROWS,GL_PACK_SKIP_PIXELS,GL_PACK_IMAGE_HEIGHT,GL_PACK_SKIP_IMAGES,GL_PACK_SWAP_BYTES,GL_PACK_LSB_FIRST};
    private final GlDevice device;private final int framebuffer,readBuffer,buffer;private final int[] values=new int[PARAMETERS.length];
    GlPackState(GlDevice device) {
        this.device=device;device.current();device.check("incoming readback state");framebuffer=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);readBuffer=glGetInteger(GL_READ_BUFFER);buffer=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        for(int i=0;i<PARAMETERS.length;i++)values[i]=glGetInteger(PARAMETERS[i]);device.check("readback state capture");
    }
    void tightlyPacked() { for(int parameter:PARAMETERS)glPixelStorei(parameter,parameter==GL_PACK_ALIGNMENT?1:0); }
    @Override public void close() {
        device.current();glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer);glReadBuffer(readBuffer);glBindBuffer(GL_PIXEL_PACK_BUFFER,buffer);
        for(int i=0;i<PARAMETERS.length;i++)glPixelStorei(PARAMETERS[i],values[i]);device.check("readback state restoration");
    }
}
