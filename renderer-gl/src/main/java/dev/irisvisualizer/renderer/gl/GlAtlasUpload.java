package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Copy-on-write GPU atlas update. Source pages are read-only; only complete new frames may escape. */
public final class GlAtlasUpload implements AutoCloseable {
    public enum Phase { VALIDATING, READ_FRAMEBUFFER, STORAGE, COPY_ROWS, PATCH_ROWS, FINISHING, READY, TAKEN, CANCELLED, FAILED, CLOSED }
    public record Progress(Phase phase,int completedPages,int changedPages,long copiedBytes,long totalCopyBytes,long uploadedBytes,long totalUploadBytes) {}
    public record Step(Progress progress,int copiedBytes,int uploadedBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds) {}
    private final GlScene scene;private final GlDevice device;private final Object expectedToken;private final int changedCount;private final long totalCopy,totalUpload;
    private AtlasFramePlan plan;private GlAtlasFrame source,completed;private GlDevice.Allocation work,framebuffer,pendingPage;
    private List<GlAtlasFrame.Page> pages;private final List<GlAtlasFrame.Page> owned;private Phase phase=Phase.VALIDATING;
    private int validation,pageCursor,copyRow,patchCursor,patchRow;private long copied,uploaded;
    private GlAtlasUpload(GlAtlasFrame original,AtlasFramePlan plan,GlAtlasFrame source,GlDevice.Allocation work) {
        scene=original.scene();device=scene.device;expectedToken=original.token();this.plan=plan;this.source=source;this.work=work;
        changedCount=plan.changedPages().size();totalCopy=plan.copiedBytes();totalUpload=plan.update().bytes();pages=new ArrayList<>(source.pages());owned=new ArrayList<>(changedCount);
    }
    /** Metadata/liveness checks only; no source retain, allocation journal or GPU storage is created. */
    static void validate(GlAtlasFrame current,AtlasFramePlan plan) {
        Objects.requireNonNull(current);Objects.requireNonNull(plan);var raster=current.raster();
        if(plan.requiresPlaceholder())throw new GlFailure(GlFailure.Code.STATE,"Atlas CPU frame preparation failed");
        if(raster!=plan.expected()||plan.raster().orElseThrow().layout()!=raster.layout())throw new GlFailure(GlFailure.Code.STALE,"Atlas update does not target the exact current CPU raster");
    }
    public static GlAtlasUpload begin(GlAtlasFrame current,AtlasFramePlan plan) {
        validate(current,plan);var scene=current.scene();var device=scene.device;
        GlAtlasFrame source=null;GlDevice.Allocation work=null;
        try {
            work=device.reserve(0,plan.extraCpuBytes(),0,0,0,0,0,0,0,null);work.retained=plan;source=current.retain();device.depend(work,source.journal());return new GlAtlasUpload(current,plan,source,work);
        }catch(RuntimeException|Error failure) {
            if(work!=null)try { device.retire(work); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(source!=null)try { source.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    /** Consumes independent CPU plan ownership through destination admission or rejection. */
    public static GlAtlasUpload begin(GlAtlasFrame current,RenderInput<AtlasFramePlan> input) {
        Objects.requireNonNull(input);GlAtlasUpload result=null;
        try(input;var source=input.retain()) {
            Objects.requireNonNull(current);var memory=current.scene().device.memory().orElse(null);
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked atlas plan requires the same device coordinator");
            result=begin(current,source.value());
        }catch(RuntimeException|Error failure) {
            if(result!=null)try { result.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            throw failure;
        }
        return result;
    }
    public Progress progress() { return new Progress(phase,pageCursor,changedCount,copied,totalCopy,uploaded,totalUpload); }
    public boolean ready() { return phase==Phase.READY; }
    GlAtlasFrame readyFrame() { device.current();if(!ready()||completed==null)throw new GlFailure(GlFailure.Code.STATE,"No complete atlas frame is available");return completed; }
    Object expectedToken() { return expectedToken; }
    boolean ownsResources() { return work!=null||framebuffer!=null||pendingPage!=null||source!=null||completed!=null||!owned.isEmpty(); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("GPU atlas update cancelled"); }
    public Step step(GlUploadBudget budget,BooleanSupplier cancelled) {
        Objects.requireNonNull(budget);Objects.requireNonNull(cancelled);device.current();if(phase==Phase.READY)return new Step(progress(),0,0,0,0,0,0);
        if(plan==null||phase==Phase.FAILED||phase==Phase.CANCELLED)throw new GlFailure(GlFailure.Code.STATE,"Atlas update is no longer resumable");
        try { return advance(budget,cancelled); }
        catch(RuntimeException|Error failure) { try { dispose(failure instanceof CancellationException?Phase.CANCELLED:Phase.FAILED); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    private Step advance(GlUploadBudget budget,BooleanSupplier cancelled) {
        long start=System.nanoTime(),storage=0;int copiedNow=0,uploadedNow=0,actions=0,units=0;boolean finish=false;GlTransferState state=null;
        Throwable failure=null;
        try {
            device.live(scene.allocation);device.live(work);source.raster();
            steps:while(units<budget.workUnits()&&(units==0||System.nanoTime()-start<budget.nanoseconds())) {
                check(cancelled);boolean gpu=phase!=Phase.VALIDATING&&phase!=Phase.FINISHING;if(gpu&&actions>=budget.actions())break;
                if(gpu&&state==null) { state=new GlTransferState(device);GlTransferState.unpack();glActiveTexture(GL_TEXTURE0);glBindSampler(0,0); }
                switch(phase) {
                    case VALIDATING -> {
                        if(validation<changedCount) {
                            int index=plan.changedPages().get(validation++);var image=plan.raster().orElseThrow().pages().get(index);
                            if(image.width()>device.maximumTextureSize()||image.height()>device.maximumTextureSize()||image.width()*4>device.limits().stagingBytes()||plan.patches(index).isEmpty())throw new GlFailure(GlFailure.Code.CAPABILITY,"Changed atlas page exceeds admitted dimensions or has no patches");
                        } else phase=changedCount==0?Phase.FINISHING:Phase.READ_FRAMEBUFFER;
                    }
                    case READ_FRAMEBUFFER -> { framebuffer=device.reserve(0,0,0,0,0,1,0,0,0,null);device.depend(framebuffer,source.journal());framebuffer.framebuffers[0]=glGenFramebuffers();device.check("atlas copy framebuffer");phase=Phase.STORAGE; }
                    case STORAGE -> {
                        int index=plan.changedPages().get(pageCursor);var image=plan.raster().orElseThrow().pages().get(index);
                        pendingPage=device.reserve(image.bytes(),0,0,1,0,0,0,0,0,null);device.moveCpu(work,pendingPage,image.bytes(),image);
                        var page=new GlAtlasFrame.Page(pendingPage,0,image,true);owned.add(page);pendingPage=null;pages.set(index,page);
                        page.allocation.textures[0]=glGenTextures();glBindTexture(GL_TEXTURE_2D,page.allocation.textures[0]);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_BASE_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,image.width(),image.height(),0,GL_RGBA,GL_UNSIGNED_BYTE,0L);device.check("copy-on-write atlas storage");storage+=image.bytes();copyRow=0;patchCursor=0;patchRow=0;phase=Phase.COPY_ROWS;
                    }
                    case COPY_ROWS -> {
                        int index=plan.changedPages().get(pageCursor);var target=pages.get(index);int rowBytes=target.pixels.width()*4,capacity=Math.min(65536,budget.transferBytes()-copiedNow-uploadedNow),rows=Math.min(target.pixels.height()-copyRow,capacity/rowBytes);if(rows==0)break steps;
                        glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer.framebuffers[0]);glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,source.pages().get(index).texture(device),0);
                        Throwable copyFailure=null;
                        try {
                            glReadBuffer(GL_COLOR_ATTACHMENT0);if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new GlFailure(GlFailure.Code.GL_ERROR,"Atlas source framebuffer is incomplete");glBindTexture(GL_TEXTURE_2D,target.texture(device));glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,copyRow,0,copyRow,target.pixels.width(),rows);device.check("bounded atlas page copy");
                        }catch(RuntimeException|Error error) { copyFailure=error;throw error; }
                        finally {
                            try { glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,0,0);device.check("atlas source detachment"); }
                            catch(RuntimeException|Error cleanup) { if(copyFailure==null)throw cleanup;if(cleanup!=copyFailure)copyFailure.addSuppressed(cleanup); }
                        }
                        copyRow+=rows;int bytes=rows*rowBytes;copiedNow+=bytes;copied+=bytes;if(copyRow==target.pixels.height())phase=Phase.PATCH_ROWS;
                    }
                    case PATCH_ROWS -> {
                        int index=plan.changedPages().get(pageCursor);var patch=plan.patches(index).get(patchCursor);int capacity=Math.min(65536,Math.min(device.limits().stagingBytes(),budget.transferBytes()-copiedNow-uploadedNow)),rows=Math.min(patch.pixels().height()-patchRow,capacity/patch.rowBytes());if(rows==0)break steps;
                        int bytes=rows*patch.rowBytes();var stage=device.staging();stage.limit(bytes);stage.put(patch.pixels().pixels().slice(patchRow*patch.rowBytes(),bytes));stage.flip();glBindTexture(GL_TEXTURE_2D,pages.get(index).texture(device));glTexSubImage2D(GL_TEXTURE_2D,0,patch.x(),patch.y()+patchRow,patch.pixels().width(),rows,GL_RGBA,GL_UNSIGNED_BYTE,stage);device.check("bounded atlas patch rows");uploadedNow+=bytes;uploaded+=bytes;patchRow+=rows;
                        if(patchRow==patch.pixels().height()) { patchCursor++;patchRow=0;if(patchCursor==plan.patches(index).size()) { pageCursor++;phase=pageCursor==changedCount?Phase.FINISHING:Phase.STORAGE; } }
                    }
                    case FINISHING -> { if(copied!=totalCopy||uploaded!=totalUpload||owned.size()!=changedCount||work.cpu!=plan.update().bytes())throw new GlFailure(GlFailure.Code.STATE,"GPU atlas update does not match its complete CPU plan");device.check("complete GPU atlas update");finish=true; }
                    default -> throw new GlFailure(GlFailure.Code.STATE,"Unexpected GPU atlas phase");
                }
                units++;if(gpu)actions++;if(finish)break;
            }
        }catch(RuntimeException|Error error) { failure=error;throw error; }
        finally {
            if(state!=null)try { state.close(); }
            catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        }
        if(finish) { check(cancelled);completed=GlAtlasFrame.create(scene,plan.raster().orElseThrow(),pages);releaseWork();phase=Phase.READY; }
        return new Step(progress(),copiedNow,uploadedNow,storage,actions,units,System.nanoTime()-start);
    }
    public GlAtlasFrame take(GlAtlasFrame current) {
        Objects.requireNonNull(current);device.current();if(phase!=Phase.READY||completed==null)throw new GlFailure(GlFailure.Code.STATE,"No complete atlas frame is available");current.requireScene(scene);
        if(current.token()!=expectedToken)throw new GlFailure(GlFailure.Code.STALE,"Atlas adoption baseline has changed");var result=completed;completed=null;phase=Phase.TAKEN;return result;
    }
    private void releaseWork() {
        Throwable failure=null;
        if(framebuffer!=null)try { device.retire(framebuffer);framebuffer=null; }catch(RuntimeException|Error error) { if(framebuffer.retired||framebuffer.released)framebuffer=null;failure=error; }
        if(work!=null)try { device.retire(work);work=null; }catch(RuntimeException|Error error) { if(work.retired||work.released)work=null;if(failure==null)failure=error;else if(error!=failure)failure.addSuppressed(error); }
        if(source!=null)try { source.close();source=null; }catch(RuntimeException|Error error) { if(source.closedOrReleased())source=null;if(failure==null)failure=error;else if(error!=failure)failure.addSuppressed(error); }
        if(pendingPage!=null)try { device.retire(pendingPage);pendingPage=null; }catch(RuntimeException|Error error) { if(pendingPage.retired||pendingPage.released)pendingPage=null;if(failure==null)failure=error;else if(error!=failure)failure.addSuppressed(error); }
        for(int i=0;i<owned.size();) {
            var page=owned.get(i);
            try { page.release(device);owned.remove(i); }
            catch(RuntimeException|Error error) { if(page.references==0)owned.remove(i);else i++;if(failure==null)failure=error;else if(error!=failure)failure.addSuppressed(error); }
        }
        if(work==null) { plan=null;if(pages!=null)pages.clear();pages=null; }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    private void dispose(Phase terminal) {
        Throwable failure=null;try { releaseWork(); }catch(RuntimeException|Error error) { failure=error; }
        if(completed!=null)try { completed.close();completed=null; }catch(RuntimeException|Error error) { if(completed.closedOrReleased())completed=null;if(failure==null)failure=error;else if(error!=failure)failure.addSuppressed(error); }
        phase=terminal;if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    private boolean nativeReleased() {
        if(work!=null&&!work.released||framebuffer!=null&&!framebuffer.released||pendingPage!=null&&!pendingPage.released||source!=null&&!source.closedOrReleased()||completed!=null&&!completed.closedOrReleased())return false;
        for(int i=0;i<owned.size();i++)if(!owned.get(i).allocation.released)return false;return true;
    }
    @Override public void close() { if(phase==Phase.TAKEN||phase==Phase.CLOSED&&!ownsResources())return;if(!nativeReleased())device.current();dispose(Phase.CLOSED); }
}
