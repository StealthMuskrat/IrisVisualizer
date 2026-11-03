package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.WorldScene;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.glfwGetCurrentContext;
import static org.lwjgl.opengl.GL33C.*;

/** Context-thread owner, bounded staging and fence-delayed resource accounting. Creates no context. */
public final class GlDevice implements AutoCloseable {
    private static final long MIB=1024L*1024;
    public record Limits(long gpuBytes,long workingBytes,int stagingBytes,int allocations,int names,int targetDimension,long targetPixels) {
        public static final Limits DEFAULT=new Limits(512*MIB,1024*MIB,4*1024*1024,64,65536,4096,16777216);
        public Limits { if(gpuBytes<0||gpuBytes>512*MIB||workingBytes<0||workingBytes>1024*MIB||stagingBytes<65536||stagingBytes>4*MIB||stagingBytes%4!=0||allocations<1||allocations>64||names<1||names>65536||targetDimension<1||targetDimension>4096||targetPixels<1||targetPixels>16777216)throw new IllegalArgumentException("GPU limits exceed backend bounds"); }
    }
    public record Stats(long gpuBytes,long cpuPayloadBytes,int stagingBytes,int allocations,int names,int retired,int quarantined) {
        public long knownWorkingBytes() { return gpuBytes+cpuPayloadBytes+stagingBytes; }
    }
    static final class Allocation {
        int[] buffers,textures,vaos,framebuffers,renderbuffers,programs,shaders;long gpu,cpu;int names;WorldScene.Result owner;Object retained;
        boolean retired,released,quarantined;long fence,transferFence;final List<Allocation> dependencies=new ArrayList<>();int dependents;
        Allocation(long gpu,long cpu,int buffers,int textures,int vaos,int framebuffers,int renderbuffers,int programs,int shaders,WorldScene.Result owner) {
            this.gpu=gpu;this.cpu=cpu;this.owner=owner;this.buffers=new int[buffers];this.textures=new int[textures];this.vaos=new int[vaos];this.framebuffers=new int[framebuffers];this.renderbuffers=new int[renderbuffers];this.programs=new int[programs];this.shaders=new int[shaders];names=buffers+textures+vaos+framebuffers+renderbuffers+programs+shaders;
        }
    }
    private final Thread thread=Thread.currentThread();private final long context;private final GLCapabilities capabilities;private final Limits limits;
    private final int maximumTexture,maximumRenderbuffer;private final List<Allocation> allocations=new ArrayList<>();private ByteBuffer staging;
    private long gpuBytes,cpuBytes;private int names;private boolean closed;
    public GlDevice() { this(Limits.DEFAULT); }
    public GlDevice(Limits limits) {
        this.limits=Objects.requireNonNull(limits);context=glfwGetCurrentContext();if(context==0)throw new GlFailure(GlFailure.Code.CONTEXT,"A current GLFW OpenGL context is required");
        try { capabilities=GL.getCapabilities(); }catch(IllegalStateException missing) { throw new GlFailure(GlFailure.Code.CONTEXT,"Initialize LWJGL capabilities for the current context first"); }
        if(!capabilities.OpenGL33||(glGetInteger(GL_CONTEXT_PROFILE_MASK)&GL_CONTEXT_CORE_PROFILE_BIT)==0)throw new GlFailure(GlFailure.Code.CAPABILITY,"The backend requires an OpenGL 3.3 core context");
        maximumTexture=glGetInteger(GL_MAX_TEXTURE_SIZE);maximumRenderbuffer=glGetInteger(GL_MAX_RENDERBUFFER_SIZE);check("device capabilities");
        if(limits.stagingBytes()>limits.workingBytes())throw new GlFailure(GlFailure.Code.LIMIT,"Staging exceeds the working budget");
        try { staging=MemoryUtil.memAlloc(limits.stagingBytes()).order(ByteOrder.nativeOrder()); }catch(OutOfMemoryError exhausted) { throw new GlFailure(GlFailure.Code.MEMORY,"Could not allocate bounded native upload staging"); }
    }
    public Limits limits() { return limits; }
    public int maximumTextureSize() { return maximumTexture; }
    public int maximumRenderbufferSize() { return maximumRenderbuffer; }
    boolean indexedViewports() { return capabilities.OpenGL41||capabilities.GL_ARB_viewport_array; }
    boolean coreIndexedBlend() { return capabilities.OpenGL40; }
    boolean indexedBlend() { return coreIndexedBlend()||capabilities.GL_ARB_draw_buffers_blend; }
    void current() {
        if(closed)throw new GlFailure(GlFailure.Code.STATE,"GPU device is closed");
        if(Thread.currentThread()!=thread||glfwGetCurrentContext()!=context)throw new GlFailure(GlFailure.Code.CONTEXT,"GPU work must use its original thread and GLFW context");
        GLCapabilities current;try { current=GL.getCapabilities(); }catch(IllegalStateException missing) { throw new GlFailure(GlFailure.Code.CONTEXT,"The original LWJGL capabilities are no longer current"); }
        if(current!=capabilities)throw new GlFailure(GlFailure.Code.CONTEXT,"GPU work must use its original LWJGL capabilities");
    }
    void check(String operation) { int error=glGetError();if(error!=GL_NO_ERROR)throw new GlFailure(GlFailure.Code.GL_ERROR,operation+" failed with OpenGL error 0x"+Integer.toHexString(error)); }
    ByteBuffer staging() { current();staging.clear();return staging; }
    boolean ownsCpu(WorldScene.Result owner) { current();for(var allocation:allocations)if(allocation.owner==owner)return true;return false; }
    Allocation reserve(long gpu,long cpu,int buffers,int textures,int vaos,int framebuffers,int renderbuffers,int programs,int shaders,WorldScene.Result owner) {
        current();collect();int[] counts={buffers,textures,vaos,framebuffers,renderbuffers,programs,shaders};long total=0;for(int count:counts) { if(count<0||count>limits.names())throw new GlFailure(GlFailure.Code.LIMIT,"GPU object count budget exceeded");total+=count; }
        if(gpu<0||cpu<0||gpu>limits.gpuBytes()-gpuBytes||cpu>limits.workingBytes()-cpuBytes||gpuBytes+gpu+cpuBytes+cpu+limits.stagingBytes()>limits.workingBytes()||allocations.size()>=limits.allocations()||total>limits.names()-names)
            throw new GlFailure(GlFailure.Code.LIMIT,"GPU, retained CPU, staging or object budget exceeded; retired resources remain charged");
        var allocation=new Allocation(gpu,cpu,buffers,textures,vaos,framebuffers,renderbuffers,programs,shaders,owner);allocations.add(allocation);gpuBytes+=gpu;cpuBytes+=cpu;names+=allocation.names;return allocation;
    }
    void live(Allocation allocation) { current();if(allocation.retired||allocation.released)throw new GlFailure(GlFailure.Code.STATE,"GPU resource is retired or released"); }
    /** Upgrade a CPU-only journal after incremental validation, before any scene GL name is made. */
    void expand(Allocation allocation,long gpu,int buffers,int textures,int vaos) {
        live(allocation);collect();if(allocation.gpu!=0||allocation.names!=0)throw new GlFailure(GlFailure.Code.STATE,"Upload reservation was already expanded");
        long added=(long)buffers+textures+vaos;
        if(gpu<0||buffers<0||textures<0||vaos<0||added>limits.names()-names||gpu>limits.gpuBytes()-gpuBytes||gpuBytes+gpu+cpuBytes+limits.stagingBytes()>limits.workingBytes())throw new GlFailure(GlFailure.Code.LIMIT,"Validated GPU scene exceeds active/retired resource admission limits");
        int[] nextBuffers=new int[buffers],nextTextures=new int[textures],nextVaos=new int[vaos];
        allocation.buffers=nextBuffers;allocation.textures=nextTextures;allocation.vaos=nextVaos;allocation.gpu=gpu;allocation.names=(int)added;gpuBytes+=gpu;names+=(int)added;
    }
    void depend(Allocation child,Allocation parent) {
        live(child);live(parent);if(child.dependencies.contains(parent))return;
        if(reaches(parent,child,new IdentityHashMap<>()))throw new GlFailure(GlFailure.Code.STATE,"Cyclic GPU resource dependency");
        child.dependencies.add(parent);parent.dependents++;
    }
    private boolean reaches(Allocation from,Allocation target,IdentityHashMap<Allocation,Boolean> seen) {
        if(from==target)return true;if(seen.put(from,Boolean.TRUE)!=null)return false;for(var parent:from.dependencies)if(reaches(parent,target,seen))return true;return false;
    }
    /** Move an already-admitted CPU page charge; no aggregate byte allowance is released or added. */
    void moveCpu(Allocation from,Allocation to,long bytes,Object retained) {
        live(from);live(to);Objects.requireNonNull(retained);
        if(from==to||bytes<0||bytes>from.cpu||to.cpu!=0||to.retained!=null)throw new GlFailure(GlFailure.Code.STATE,"Invalid CPU payload reservation transfer");
        from.cpu-=bytes;to.cpu=bytes;to.retained=retained;
    }
    void retire(Allocation allocation) {
        if(allocation.released)return;current();if(allocation.retired)return;
        if(allocation.names==0&&allocation.gpu==0&&allocation.dependents==0) { release(allocation);allocations.remove(allocation);return; }
        allocation.retired=true;
        // Every allocation already has a journal slot, including failed uploads. A failed fence
        // leaves it quarantined and charged until successful explicit device teardown.
        try { allocation.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);check("resource retirement fence");glFlush();check("resource retirement flush"); }
        catch(RuntimeException failure) { allocation.quarantined=true;throw failure; }
    }
    public void collect() {
        current();var iterator=allocations.iterator();
        while(iterator.hasNext()) {
            var allocation=iterator.next();if(!allocation.retired||allocation.fence==0||allocation.quarantined||allocation.dependents!=0)continue;
            try {
                int status=glClientWaitSync(allocation.fence,GL_SYNC_FLUSH_COMMANDS_BIT,0);check("nonblocking resource retirement");
                if(status==GL_TIMEOUT_EXPIRED)continue;if(status!=GL_ALREADY_SIGNALED&&status!=GL_CONDITION_SATISFIED)throw new GlFailure(GlFailure.Code.GL_ERROR,"GPU retirement fence could not be observed");
                delete(allocation);glDeleteSync(allocation.fence);check("retirement fence deletion");allocation.fence=0;release(allocation);iterator.remove();
            }catch(RuntimeException failure) { allocation.quarantined=true;throw failure; }
        }
    }
    private void delete(Allocation allocation) {
        if(allocation.transferFence!=0) { glDeleteSync(allocation.transferFence);check("transfer fence deletion");allocation.transferFence=0; }
        for(int i=0;i<allocation.vaos.length;i++)if(allocation.vaos[i]!=0) { glDeleteVertexArrays(allocation.vaos[i]);check("VAO deletion");allocation.vaos[i]=0; }
        for(int i=0;i<allocation.framebuffers.length;i++)if(allocation.framebuffers[i]!=0) { glDeleteFramebuffers(allocation.framebuffers[i]);check("framebuffer deletion");allocation.framebuffers[i]=0; }
        for(int i=0;i<allocation.renderbuffers.length;i++)if(allocation.renderbuffers[i]!=0) { glDeleteRenderbuffers(allocation.renderbuffers[i]);check("renderbuffer deletion");allocation.renderbuffers[i]=0; }
        for(int i=0;i<allocation.buffers.length;i++)if(allocation.buffers[i]!=0) { glDeleteBuffers(allocation.buffers[i]);check("buffer deletion");allocation.buffers[i]=0; }
        for(int i=0;i<allocation.textures.length;i++)if(allocation.textures[i]!=0) { glDeleteTextures(allocation.textures[i]);check("texture deletion");allocation.textures[i]=0; }
        for(int i=0;i<allocation.programs.length;i++)if(allocation.programs[i]!=0) { glDeleteProgram(allocation.programs[i]);check("program deletion");allocation.programs[i]=0; }
        for(int i=0;i<allocation.shaders.length;i++)if(allocation.shaders[i]!=0) { glDeleteShader(allocation.shaders[i]);check("shader deletion");allocation.shaders[i]=0; }
    }
    private void release(Allocation allocation) {
        if(allocation.owner!=null) { allocation.owner.close();allocation.owner=null; }
        allocation.retained=null;for(var parent:allocation.dependencies)parent.dependents--;allocation.dependencies.clear();allocation.released=true;gpuBytes-=allocation.gpu;cpuBytes-=allocation.cpu;names-=allocation.names;
    }
    public Stats stats() { current();int retired=0,quarantined=0;for(var allocation:allocations)if(allocation.retired) { retired++;if(allocation.fence==0||allocation.quarantined)quarantined++; }return new Stats(gpuBytes,cpuBytes,limits.stagingBytes(),allocations.size(),names,retired,quarantined); }
    /** Explicit teardown may wait for the GPU. Call before destroying the original current context. */
    @Override public void close() {
        if(closed)return;current();glFinish();check("GPU device teardown synchronization");
        while(!allocations.isEmpty()) {
            boolean progress=false;var iterator=allocations.iterator();
            while(iterator.hasNext()) { var allocation=iterator.next();if(allocation.dependents!=0)continue;delete(allocation);if(allocation.fence!=0) { glDeleteSync(allocation.fence);check("teardown fence deletion");allocation.fence=0; }release(allocation);iterator.remove();progress=true; }
            if(!progress)throw new GlFailure(GlFailure.Code.STATE,"GPU dependency teardown cannot progress");
        }
        MemoryUtil.memFree(staging);staging=null;closed=true;
    }
}
