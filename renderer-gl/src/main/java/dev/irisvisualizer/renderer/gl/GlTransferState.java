package dev.irisvisualizer.renderer.gl;

import static org.lwjgl.opengl.GL33C.*;

/** Transfer/creation bindings only: no program, viewport, raster, blend or depth mutation. */
final class GlTransferState implements AutoCloseable {
    private final GlDevice device;
    private final int active,texture,sampler,vao,array,unpack,draw,read,renderbuffer;
    private final GlPixelStore pixels;
    GlTransferState(GlDevice device) {
        this.device=device;device.current();device.check("incoming transfer state");
        active=glGetInteger(GL_ACTIVE_TEXTURE);int savedTexture;Throwable failure=null;
        try { glActiveTexture(GL_TEXTURE0);savedTexture=glGetInteger(GL_TEXTURE_BINDING_2D); }
        catch(RuntimeException|Error error) { failure=error;throw error; }
        finally {
            try { glActiveTexture(active); }
            catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        }
        texture=savedTexture;sampler=glGetIntegeri(GL_SAMPLER_BINDING,0);vao=glGetInteger(GL_VERTEX_ARRAY_BINDING);array=glGetInteger(GL_ARRAY_BUFFER_BINDING);unpack=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        pixels=GlPixelStore.unpack();draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);renderbuffer=glGetInteger(GL_RENDERBUFFER_BINDING);device.check("transfer state capture");
    }
    static void unpack() { glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);GlPixelStore.tightlyUnpacked(); }
    @Override public void close() {
        device.current();glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindRenderbuffer(GL_RENDERBUFFER,renderbuffer);
        glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,array);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);pixels.restore();
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(0,sampler);glActiveTexture(active);device.check("transfer state restoration");
    }
}
