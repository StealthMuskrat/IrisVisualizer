package dev.irisvisualizer.renderer.gl;

import static org.lwjgl.opengl.GL33C.*;

/** Readback-only state guard; never changes host framebuffer attachments or buffer storage. */
final class GlPackState implements AutoCloseable {
    private final GlDevice device;private final int framebuffer,readBuffer,buffer;private final GlPixelStore pixels;
    GlPackState(GlDevice device) {
        this.device=device;device.current();device.check("incoming readback state");framebuffer=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);readBuffer=glGetInteger(GL_READ_BUFFER);buffer=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        pixels=GlPixelStore.pack();device.check("readback state capture");
    }
    void tightlyPacked() { pixels.tightlyPacked(); }
    @Override public void close() {
        device.current();glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer);glReadBuffer(readBuffer);glBindBuffer(GL_PIXEL_PACK_BUFFER,buffer);
        pixels.restore();device.check("readback state restoration");
    }
}
