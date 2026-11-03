package dev.irisvisualizer.renderer.gl;

import static org.lwjgl.opengl.GL33C.*;
import java.util.Objects;

/** Owned single-sample RGBA8/depth24 offscreen target; never changes the host window framebuffer. */
public final class GlTarget implements AutoCloseable {
    final GlDevice device;final GlDevice.Allocation allocation;private final int width,height;
    boolean complete=true;
    private GlTarget(GlDevice device,GlDevice.Allocation allocation,int width,int height) { this.device=device;this.allocation=allocation;this.width=width;this.height=height; }
    public static GlTarget create(GlDevice device,int width,int height) {
        Objects.requireNonNull(device);device.current();
        if(width<1||height<1||width>device.limits().targetDimension()||height>device.limits().targetDimension()||width>device.maximumTextureSize()||height>device.maximumTextureSize()||width>device.maximumRenderbufferSize()||height>device.maximumRenderbufferSize()||(long)width*height>device.limits().targetPixels())throw new GlFailure(GlFailure.Code.LIMIT,"Offscreen target exceeds configured or hardware dimensions");
        var allocation=device.reserve((long)width*height*8,0,0,1,0,1,1,0,0,null);
        try(var state=new GlState(device)) {
            GlTransferState.unpack();glActiveTexture(GL_TEXTURE0);allocation.textures[0]=glGenTextures();glBindTexture(GL_TEXTURE_2D,allocation.textures[0]);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,0L);device.check("target color allocation");
            allocation.renderbuffers[0]=glGenRenderbuffers();glBindRenderbuffer(GL_RENDERBUFFER,allocation.renderbuffers[0]);glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,width,height);device.check("target depth allocation");
            allocation.framebuffers[0]=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,allocation.framebuffers[0]);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,allocation.textures[0],0);glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,allocation.renderbuffers[0]);glDrawBuffer(GL_COLOR_ATTACHMENT0);glReadBuffer(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new GlFailure(GlFailure.Code.GL_ERROR,"Offscreen target is incomplete");state.drawing();glViewport(0,0,width,height);glClearBufferfv(GL_COLOR,0,new float[]{0,0,0,0});glClearBufferfv(GL_DEPTH,0,new float[]{1});device.check("offscreen target creation");return new GlTarget(device,allocation,width,height);
        }catch(RuntimeException|Error failure) { try { device.retire(allocation); }catch(RuntimeException retirement) { failure.addSuppressed(retirement); }throw failure; }
    }
    public int width() { return width; }
    public int height() { return height; }
    /** Borrowed texture ID; only valid while this target is live in its original context. */
    public int textureId() { device.live(allocation);if(!complete)throw new GlFailure(GlFailure.Code.STATE,"Offscreen frame did not complete successfully");return allocation.textures[0]; }
    public boolean frameComplete() { device.live(allocation);return complete; }
    @Override public void close() { device.retire(allocation); }
}
