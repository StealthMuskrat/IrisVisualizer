package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.WorldScene;
import dev.irisvisualizer.renderer.RenderMemory;
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
    /** Hard operation/work limits and a cooperative elapsed-time target for automatic retirement. */
    public record CollectionBudget(int actions,int workUnits,long nanoseconds) {
        public static final CollectionBudget DEFAULT=new CollectionBudget(64,512,1_000_000);
        public static final CollectionBudget MAXIMUM=new CollectionBudget(1024,262144,50_000_000);
        public CollectionBudget { if(actions<1||actions>1024||workUnits<1||workUnits>262144||nanoseconds<1||nanoseconds>50_000_000)throw new IllegalArgumentException("Retirement collection budget exceeds backend bounds"); }
    }
    /** One work unit visits an allocation and performs at most one poll, slot check, sync deletion or release. */
    public record CollectionStep(int workUnits,int fencePolls,int namesChecked,int namesDeleted,int syncsDeleted,int allocationsReleased,long elapsedNanoseconds) {
        public CollectionStep {
            if(workUnits<0||fencePolls<0||namesChecked<0||namesDeleted<0||namesDeleted>namesChecked||syncsDeleted<0||allocationsReleased<0||(long)fencePolls+namesChecked+syncsDeleted+allocationsReleased>workUnits||elapsedNanoseconds<0)throw new IllegalArgumentException("Invalid retirement work receipt");
        }
        public int actions() { return fencePolls+namesDeleted+syncsDeleted; }
    }
    /** Immutable context provenance for diagnostics; contains no native/context handle or readiness claim. */
    public record Driver(String vendor,String renderer,String version,String shadingLanguage,int major,int minor,int contextFlags,int profileMask,
                         int maximumTexture,int maximumRenderbuffer,int maximumVertexAttributes,int maximumTextureUnits,List<String> optionalFeatures) {
        public Driver {
            for(var value:List.of(vendor,renderer,version,shadingLanguage))if(value.isBlank()||value.length()>4096)throw new IllegalArgumentException("Invalid OpenGL driver identity");
            optionalFeatures=List.copyOf(optionalFeatures);
            if(major<3||minor<0||maximumTexture<1||maximumRenderbuffer<1||maximumVertexAttributes<1||maximumTextureUnits<1||optionalFeatures.size()>16)throw new IllegalArgumentException("Invalid OpenGL capability receipt");
            for(var feature:optionalFeatures)if(feature.isBlank()||feature.length()>128)throw new IllegalArgumentException("Invalid OpenGL feature receipt");
        }
    }
    static final class Allocation {
        int[] buffers,textures,vaos,framebuffers,renderbuffers,programs,shaders;long gpu,cpu;int names;WorldScene.Result owner;Object retained;
        boolean retired,released,quarantined,retirementReady,unstartedStorage;int deleteGroup,deleteIndex;long fence,transferFence;final List<Allocation> dependencies=new ArrayList<>();int dependents;
        Allocation(long gpu,long cpu,int buffers,int textures,int vaos,int framebuffers,int renderbuffers,int programs,int shaders,WorldScene.Result owner) {
            this.gpu=gpu;this.cpu=cpu;this.owner=owner;this.buffers=new int[buffers];this.textures=new int[textures];this.vaos=new int[vaos];this.framebuffers=new int[framebuffers];this.renderbuffers=new int[renderbuffers];this.programs=new int[programs];this.shaders=new int[shaders];names=buffers+textures+vaos+framebuffers+renderbuffers+programs+shaders;
        }
    }
    private final Thread thread=Thread.currentThread();private final long context;private final GLCapabilities capabilities;private final Limits limits;private final RenderMemory memory;private final RenderMemory.Account admission;
    private final int maximumTexture,maximumRenderbuffer;private final Driver driver;private List<Allocation> allocations=new ArrayList<>();private ByteBuffer staging;
    private long gpuBytes,cpuBytes;private int names,collectionCursor;private boolean closing,closed;
    public GlDevice() { this(Limits.DEFAULT); }
    public GlDevice(Limits limits) { this(limits,null); }
    public GlDevice(Limits limits,RenderMemory memory) {
        this.limits=Objects.requireNonNull(limits);this.memory=memory;context=glfwGetCurrentContext();if(context==0)throw new GlFailure(GlFailure.Code.CONTEXT,"A current GLFW OpenGL context is required");
        try { capabilities=GL.getCapabilities(); }catch(IllegalStateException missing) { throw new GlFailure(GlFailure.Code.CONTEXT,"Initialize LWJGL capabilities for the current context first"); }
        if(!capabilities.OpenGL33||(glGetInteger(GL_CONTEXT_PROFILE_MASK)&GL_CONTEXT_CORE_PROFILE_BIT)==0)throw new GlFailure(GlFailure.Code.CAPABILITY,"The backend requires an OpenGL 3.3 core context");
        maximumTexture=glGetInteger(GL_MAX_TEXTURE_SIZE);maximumRenderbuffer=glGetInteger(GL_MAX_RENDERBUFFER_SIZE);
        var features=new ArrayList<String>();
        if(indexedViewports())features.add("indexed-viewports");if(indexedBlend())features.add("indexed-blend");if(clipControl())features.add("clip-control");
        if(depthBounds())features.add("depth-bounds");if(fixedRestart())features.add("fixed-restart");if(sampleShading())features.add("sample-shading");
        String vendor=identity(GL_VENDOR),renderer=identity(GL_RENDERER),version=identity(GL_VERSION),shading=identity(GL_SHADING_LANGUAGE_VERSION);
        int major=glGetInteger(GL_MAJOR_VERSION),minor=glGetInteger(GL_MINOR_VERSION),flags=glGetInteger(GL_CONTEXT_FLAGS),profile=glGetInteger(GL_CONTEXT_PROFILE_MASK),attributes=glGetInteger(GL_MAX_VERTEX_ATTRIBS),units=glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);check("device capabilities");
        driver=new Driver(vendor,renderer,version,shading,major,minor,flags,profile,maximumTexture,maximumRenderbuffer,attributes,units,features);
        if(limits.stagingBytes()>limits.workingBytes())throw new GlFailure(GlFailure.Code.LIMIT,"Staging exceeds the working budget");
        admission=memory==null?null:openAdmission(memory);
        try {
            admit(limits.stagingBytes());staging=MemoryUtil.memAlloc(limits.stagingBytes());staging.order(ByteOrder.nativeOrder());
        }catch(RuntimeException|Error failure) {
            if(staging!=null)try { MemoryUtil.memFree(staging);staging=null; }
            catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(admission!=null) {
                // A failed native free must not make its possibly live payload look uncharged.
                if(staging==null)try { admission.retained(0); }
                catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
                try { admission.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            }
            if(failure instanceof OutOfMemoryError) {
                var mapped=new GlFailure(GlFailure.Code.MEMORY,"Could not allocate bounded native upload staging");mapped.initCause(failure);throw mapped;
            }
            throw failure;
        }
    }
    /** Borrowed optional shared admission; frame/upload resources are charged automatically by this device. */
    public Optional<RenderMemory> memory() { return Optional.ofNullable(memory); }
    private static RenderMemory.Account openAdmission(RenderMemory memory) {
        try { return memory.open("OpenGL device"); }
        catch(RenderMemory.LimitException failure) { throw new GlFailure(GlFailure.Code.LIMIT,failure.getMessage()); }
        catch(IllegalStateException failure) { throw new GlFailure(GlFailure.Code.STATE,failure.getMessage()); }
    }
    private void admit(long bytes) {
        if(admission==null)return;
        try { admission.retained(bytes); }
        catch(RenderMemory.LimitException failure) { throw new GlFailure(GlFailure.Code.LIMIT,failure.getMessage()); }
    }
    public Limits limits() { return limits; }
    public int maximumTextureSize() { return maximumTexture; }
    public int maximumRenderbufferSize() { return maximumRenderbuffer; }
    /** Detached metadata remains readable off-thread and after device closure. */
    public Driver driver() { return driver; }
    private static String identity(int name) { var value=glGetString(name);if(value==null||value.isBlank()||value.length()>4096)throw new GlFailure(GlFailure.Code.CAPABILITY,"OpenGL driver identity is absent or exceeds its diagnostic bound");return value; }
    boolean indexedViewports() { return capabilities.OpenGL41||capabilities.GL_ARB_viewport_array; }
    boolean coreIndexedBlend() { return capabilities.OpenGL40; }
    boolean indexedBlend() { return coreIndexedBlend()||capabilities.GL_ARB_draw_buffers_blend; }
    boolean clipControl() { return capabilities.OpenGL45||capabilities.GL_ARB_clip_control; }
    boolean coreClipControl() { return capabilities.OpenGL45; }
    boolean depthBounds() { return capabilities.GL_EXT_depth_bounds_test; }
    boolean fixedRestart() { return capabilities.OpenGL43||capabilities.GL_ARB_ES3_compatibility; }
    boolean sampleShading() { return capabilities.OpenGL40||capabilities.GL_ARB_sample_shading; }
    void drawingAllowed() {
        current();
        if(capabilities.OpenGL40||capabilities.GL_ARB_transform_feedback2) {
            boolean active=glGetBoolean(org.lwjgl.opengl.ARBTransformFeedback2.GL_TRANSFORM_FEEDBACK_BUFFER_ACTIVE);
            boolean paused=glGetBoolean(org.lwjgl.opengl.ARBTransformFeedback2.GL_TRANSFORM_FEEDBACK_BUFFER_PAUSED);
            check("transform feedback draw preflight");
            if(active&&!paused)throw new GlFailure(GlFailure.Code.STATE,"End or pause host transform feedback before offscreen drawing");
        }
    }
    void current() {
        context();if(closing)throw new GlFailure(GlFailure.Code.STATE,"GPU device teardown is incomplete; retry close");
    }
    /** Teardown and diagnostic inspection may revisit the original context after the work gate closes. */
    private void context() {
        if(closed)throw new GlFailure(GlFailure.Code.STATE,"GPU device is closed");
        if(Thread.currentThread()!=thread||glfwGetCurrentContext()!=context)throw new GlFailure(GlFailure.Code.CONTEXT,"GPU work must use its original thread and GLFW context");
        GLCapabilities current;try { current=GL.getCapabilities(); }catch(IllegalStateException missing) { throw new GlFailure(GlFailure.Code.CONTEXT,"The original LWJGL capabilities are no longer current"); }
        if(current!=capabilities)throw new GlFailure(GlFailure.Code.CONTEXT,"GPU work must use its original LWJGL capabilities");
    }
    void check(String operation) { int error=glGetError();if(error!=GL_NO_ERROR)throw new GlFailure(GlFailure.Code.GL_ERROR,operation+" failed with OpenGL error 0x"+Integer.toHexString(error)); }
    ByteBuffer staging() { current();staging.clear();return staging; }
    boolean ownsCpu(WorldScene.Result owner) { current();for(var allocation:allocations)if(allocation.owner==owner)return true;return false; }
    Allocation reserve(long gpu,long cpu,int buffers,int textures,int vaos,int framebuffers,int renderbuffers,int programs,int shaders,WorldScene.Result owner) {
        current();collect(CollectionBudget.DEFAULT);int[] counts={buffers,textures,vaos,framebuffers,renderbuffers,programs,shaders};long total=0;for(int count:counts) { if(count<0||count>limits.names())throw new GlFailure(GlFailure.Code.LIMIT,"GPU object count budget exceeded");total+=count; }
        if(gpu<0||cpu<0||gpu>limits.gpuBytes()-gpuBytes||cpu>limits.workingBytes()-cpuBytes||gpuBytes+gpu+cpuBytes+cpu+limits.stagingBytes()>limits.workingBytes()||allocations.size()>=limits.allocations()||total>limits.names()-names)
            throw new GlFailure(GlFailure.Code.LIMIT,"GPU, retained CPU, staging or object budget exceeded; retired resources remain charged");
        var allocation=new Allocation(gpu,cpu,buffers,textures,vaos,framebuffers,renderbuffers,programs,shaders,owner);
        var nextAllocations=new ArrayList<Allocation>(allocations.size()+1);nextAllocations.addAll(allocations);nextAllocations.add(allocation);
        // All registry allocation precedes shared admission. The context-thread commit below allocates nothing.
        admit(gpuBytes+gpu+cpuBytes+cpu+limits.stagingBytes());
        allocations=nextAllocations;gpuBytes+=gpu;cpuBytes+=cpu;names+=allocation.names;return allocation;
    }
    void live(Allocation allocation) { current();if(allocation.retired||allocation.released)throw new GlFailure(GlFailure.Code.STATE,"GPU resource is retired or released"); }
    /** Admit complete known storage before ownership acceptance; no native names or commands are created. */
    Allocation reserveStorage(long gpu,long cpu,int buffers,int textures,int vaos,int framebuffers,int renderbuffers,int programs,int shaders) {
        return reserveStorage(gpu,cpu,buffers,textures,vaos,framebuffers,renderbuffers,programs,shaders,null);
    }
    /** A CPU scene lease is safe to refund with proven never-started storage; framebuffer slots need no payload bytes. */
    Allocation reserveStorage(long gpu,long cpu,int buffers,int textures,int vaos,int framebuffers,int renderbuffers,int programs,int shaders,WorldScene.Result owner) {
        if(gpu<0||(long)buffers+textures+vaos+framebuffers+renderbuffers+programs+shaders<=0)throw new IllegalArgumentException("Storage admission requires nonnegative payload and object slots");
        var allocation=reserve(gpu,cpu,buffers,textures,vaos,framebuffers,renderbuffers,programs,shaders,owner);allocation.unstartedStorage=true;return allocation;
    }
    /** One-way transition before the first native storage operation, even if no handle is returned. */
    void beginStorage(Allocation allocation) {
        live(allocation);if(!allocations.contains(allocation)||!allocation.unstartedStorage)throw new GlFailure(GlFailure.Code.STATE,"Storage is foreign or already started");allocation.unstartedStorage=false;
    }
    /** Upgrade a CPU-only journal after incremental validation, before any scene GL name is made. */
    void expand(Allocation allocation,long gpu,int buffers,int textures,int vaos) {
        live(allocation);collect(CollectionBudget.DEFAULT);if(allocation.gpu!=0||allocation.names!=0)throw new GlFailure(GlFailure.Code.STATE,"Upload reservation was already expanded");
        long added=(long)buffers+textures+vaos;
        if(gpu<0||buffers<0||textures<0||vaos<0||added>limits.names()-names||gpu>limits.gpuBytes()-gpuBytes||gpuBytes+gpu+cpuBytes+limits.stagingBytes()>limits.workingBytes())throw new GlFailure(GlFailure.Code.LIMIT,"Validated GPU scene exceeds active/retired resource admission limits");
        int[] nextBuffers=new int[buffers],nextTextures=new int[textures],nextVaos=new int[vaos];
        admit(gpuBytes+gpu+cpuBytes+limits.stagingBytes());
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
        // Every allocation has a journal, including CPU-only preflight and failed uploads.
        // Failed release/fencing stays quarantined until explicit synchronized device teardown.
        try {
            if(allocation.names==0&&allocation.gpu==0&&allocation.dependents==0) { release(allocation);removeAllocation(allocation);return; }
            allocation.retired=true;allocation.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);check("resource retirement fence");if(allocation.fence==0)throw new GlFailure(GlFailure.Code.GL_ERROR,"Could not create resource retirement fence");glFlush();check("resource retirement flush");
        }catch(RuntimeException|Error failure) { allocation.retired=true;allocation.quarantined=true;throw failure; }
    }
    /** Only explicitly admitted, never-started storage can refund CPU payload and source pins without a fence. */
    void discardUnstarted(Allocation allocation) {
        if(allocation.released)return;live(allocation);if(!allocations.contains(allocation))throw new GlFailure(GlFailure.Code.STATE,"Reservation belongs to another device");
        if(!allocation.unstartedStorage||allocation.dependents!=0||allocation.fence!=0||allocation.transferFence!=0)throw new GlFailure(GlFailure.Code.STATE,"Only never-started storage without dependents can be discarded");
        for(int group=0;group<7;group++)for(int name:nameArray(allocation,group))if(name!=0)throw new GlFailure(GlFailure.Code.STATE,"Started GPU storage cannot be discarded");
        try { release(allocation);removeAllocation(allocation); }
        catch(RuntimeException|Error failure) { allocation.retired=true;allocation.quarantined=true;throw failure; }
    }
    private void removeAllocation(Allocation allocation) {
        int at=allocations.indexOf(allocation);if(at<0)return;allocations.remove(at);if(at<collectionCursor)collectionCursor--;if(collectionCursor>=allocations.size())collectionCursor=0;
    }
    private static boolean collectible(Allocation allocation) {
        return allocation.retired&&!allocation.released&&!allocation.quarantined&&allocation.dependents==0&&(allocation.retirementReady||allocation.fence!=0);
    }
    /** Round-robin, zero-timeout retirement; each visited allocation costs one work unit, including skips. */
    public CollectionStep collect(CollectionBudget budget) {
        Objects.requireNonNull(budget);current();long start=System.nanoTime();int units=0,polls=0,checked=0,deleted=0,syncs=0,released=0,actions=0,idle=0;
        while(!allocations.isEmpty()&&units<budget.workUnits()&&actions<budget.actions()&&(units==0||System.nanoTime()-start<budget.nanoseconds())) {
            if(collectionCursor>=allocations.size())collectionCursor=0;var allocation=allocations.get(collectionCursor);collectionCursor=(collectionCursor+1)%allocations.size();units++;
            if(allocation.released) { removeAllocation(allocation);released++;idle=0;continue; }
            if(!collectible(allocation)) { if(++idle>=allocations.size())break;continue; }
            try {
                if(!allocation.retirementReady) {
                    int status=glClientWaitSync(allocation.fence,0,0L);polls++;actions++;check("bounded retirement fence poll");
                    if(status==GL_TIMEOUT_EXPIRED) { if(++idle>=allocations.size())break;continue; }
                    if(status!=GL_ALREADY_SIGNALED&&status!=GL_CONDITION_SATISFIED)throw new GlFailure(GlFailure.Code.GL_ERROR,"GPU retirement fence could not be observed");allocation.retirementReady=true;
                }else if(allocation.transferFence!=0) {
                    glDeleteSync(allocation.transferFence);syncs++;actions++;check("bounded transfer fence deletion");allocation.transferFence=0;
                }else if(nextName(allocation)) {
                    checked++;var array=nameArray(allocation,allocation.deleteGroup);int at=allocation.deleteIndex;
                    if(array[at]!=0) { deleteName(allocation.deleteGroup,array[at]);deleted++;actions++;check("bounded GPU object deletion");array[at]=0; }
                    allocation.deleteIndex++;
                }else if(allocation.fence!=0) {
                    glDeleteSync(allocation.fence);syncs++;actions++;check("bounded retirement fence deletion");allocation.fence=0;
                }else { release(allocation);removeAllocation(allocation);released++; }
                idle=0;
            }catch(RuntimeException|Error failure) { allocation.quarantined=true;throw failure; }
        }
        return new CollectionStep(units,polls,checked,deleted,syncs,released,System.nanoTime()-start);
    }
    private static int[] nameArray(Allocation allocation,int group) {
        return switch(group) { case 0->allocation.vaos;case 1->allocation.framebuffers;case 2->allocation.renderbuffers;case 3->allocation.buffers;case 4->allocation.textures;case 5->allocation.programs;case 6->allocation.shaders;default->throw new IllegalArgumentException("Unknown GPU object group"); };
    }
    /** Advances over at most seven array boundaries; every actual slot requires its own work unit. */
    private static boolean nextName(Allocation allocation) {
        while(allocation.deleteGroup<7&&allocation.deleteIndex>=nameArray(allocation,allocation.deleteGroup).length) { allocation.deleteGroup++;allocation.deleteIndex=0; }
        return allocation.deleteGroup<7;
    }
    private static void deleteName(int group,int name) {
        switch(group) { case 0->glDeleteVertexArrays(name);case 1->glDeleteFramebuffers(name);case 2->glDeleteRenderbuffers(name);case 3->glDeleteBuffers(name);case 4->glDeleteTextures(name);case 5->glDeleteProgram(name);case 6->glDeleteShader(name);default->throw new IllegalArgumentException("Unknown GPU object group"); }
    }
    /** Compatibility full pass; automatic work uses the bounded overload instead. */
    public void collect() {
        current();var iterator=allocations.iterator();
        try {
            while(iterator.hasNext()) {
                var allocation=iterator.next();if(allocation.released) { iterator.remove();continue; }if(!collectible(allocation))continue;
                try {
                    if(!allocation.retirementReady) {
                        int status=glClientWaitSync(allocation.fence,GL_SYNC_FLUSH_COMMANDS_BIT,0);check("nonblocking resource retirement");
                        if(status==GL_TIMEOUT_EXPIRED)continue;if(status!=GL_ALREADY_SIGNALED&&status!=GL_CONDITION_SATISFIED)throw new GlFailure(GlFailure.Code.GL_ERROR,"GPU retirement fence could not be observed");allocation.retirementReady=true;
                    }
                    delete(allocation);if(allocation.fence!=0) { glDeleteSync(allocation.fence);check("retirement fence deletion");allocation.fence=0; }release(allocation);iterator.remove();
                }catch(RuntimeException|Error failure) { allocation.quarantined=true;throw failure; }
            }
        }finally { collectionCursor=0; }
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
        allocation.retained=null;for(int i=0;i<allocation.dependencies.size();i++)allocation.dependencies.get(i).dependents--;allocation.dependencies.clear();allocation.released=true;gpuBytes-=allocation.gpu;cpuBytes-=allocation.cpu;names-=allocation.names;admit(gpuBytes+cpuBytes+limits.stagingBytes());
    }
    public Stats stats() { context();int retired=0,quarantined=0;for(var allocation:allocations)if(allocation.retired) { retired++;if(allocation.quarantined||allocation.fence==0&&!allocation.retirementReady)quarantined++; }return new Stats(gpuBytes,cpuBytes,limits.stagingBytes(),allocations.size(),names,retired,quarantined); }
    /** Explicit teardown may wait for the GPU. Call before destroying the original current context. */
    @Override public void close() {
        if(closed)return;context();
        // Bounded bookkeeping is allocated before closing the work gate or mutating resources.
        var failed=new IdentityHashMap<Allocation,Boolean>(allocations.size());closing=true;
        glFinish();check("GPU device teardown synchronization");Throwable failure=null;
        while(!allocations.isEmpty()) {
            boolean progress=false;
            for(int i=0;i<allocations.size();) {
                var allocation=allocations.get(i);
                // Release may have committed before shared-admission publication reported a failure.
                if(allocation.released) { allocations.remove(i);progress=true;continue; }
                if(allocation.dependents!=0||failed.containsKey(allocation)) { i++;continue; }
                try {
                    delete(allocation);if(allocation.fence!=0) { glDeleteSync(allocation.fence);check("teardown fence deletion");allocation.fence=0; }
                    release(allocation);allocations.remove(i);progress=true;
                }catch(RuntimeException|Error error) {
                    if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error);
                    if(allocation.released) { allocations.remove(i);progress=true; }
                    else { allocation.retired=true;allocation.quarantined=true;failed.put(allocation,Boolean.TRUE);i++; }
                }
            }
            if(!progress) {
                if(failure==null)failure=new GlFailure(GlFailure.Code.STATE,"GPU dependency teardown cannot progress");
                break;
            }
        }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
        if(staging!=null) { MemoryUtil.memFree(staging);staging=null; }
        if(admission!=null) { admission.retained(0);admission.close(); }closed=true;
    }
}
