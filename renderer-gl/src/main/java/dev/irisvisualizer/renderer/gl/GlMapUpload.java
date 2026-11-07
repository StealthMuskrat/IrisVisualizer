package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Resumable whole-row map upload; no partially populated texture can be taken. */
public final class GlMapUpload implements AutoCloseable {
    public enum Phase { STORAGE, ROWS, FINISHING, READY, TAKEN, CANCELLED, FAILED, CLOSED }
    public record Progress(Phase phase,int completedRows,int totalRows,long uploadedBytes,long totalBytes) {}
    public record Step(Progress progress,int transferBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds) {}
    private final GlDevice device;private final MapSamples.Context context;private final int width,height;private final long totalBytes;
    private GlDevice.Allocation allocation;private MapRaster raster;private GlMap completed;private Phase phase=Phase.STORAGE;private int row;private long uploaded;
    private GlMapUpload(GlDevice device,MapRaster raster,GlDevice.Allocation allocation) { this.device=device;this.raster=raster;this.allocation=allocation;context=raster.samples().context();var image=raster.image().orElseThrow();width=image.width();height=image.height();totalBytes=image.bytes(); }
    /** Source/context/capability preflight; no upload journal, texture or staging payload is allocated. */
    static void validate(GlDevice device,MapRaster raster) {
        Objects.requireNonNull(device);Objects.requireNonNull(raster);device.current();if(raster.requiresPlaceholder())throw new GlFailure(GlFailure.Code.STATE,"A complete CPU map raster is required");var image=raster.image().orElseThrow();
        dimensions(device,image.width(),image.height());
    }
    /** Original sample-grid bounds, also known before colorization; no pixel or sample scan. */
    static void dimensions(GlDevice device,int width,int height) {
        Objects.requireNonNull(device);device.current();
        if(width<1||height<1||width>device.maximumTextureSize()||height>device.maximumTextureSize()||(long)width*4>device.limits().stagingBytes())throw new GlFailure(GlFailure.Code.CAPABILITY,"Map exceeds hardware texture or bounded row-staging dimensions");
    }
    public static GlMapUpload begin(GlDevice device,MapRaster raster) {
        validate(device,raster);
        GlDevice.Allocation allocation=null;
        try { allocation=device.reserveStorage(raster.image().orElseThrow().bytes(),raster.payloadBytes(),0,1,0,0,0,0,0);allocation.retained=raster;return new GlMapUpload(device,raster,allocation); }
        catch(RuntimeException|Error failure) { if(allocation!=null)try { device.discardUnstarted(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    public MapSamples.Context context() { return context; }
    public Progress progress() { return new Progress(phase,row,height,uploaded,totalBytes); }
    public boolean ready() { return phase==Phase.READY; }
    boolean ownsResources() { return allocation!=null; }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("GPU map upload cancelled"); }
    public Step step(GlUploadBudget budget,BooleanSupplier cancelled) {
        Objects.requireNonNull(budget);Objects.requireNonNull(cancelled);device.current();if(ready())return new Step(progress(),0,0,0,0,0);
        if(allocation==null||phase==Phase.FAILED||phase==Phase.CANCELLED)throw new GlFailure(GlFailure.Code.STATE,"Map upload is no longer resumable");device.live(allocation);
        try { return advance(budget,cancelled); }catch(RuntimeException|Error failure) { try { dispose(failure instanceof CancellationException?Phase.CANCELLED:Phase.FAILED); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    private Step advance(GlUploadBudget budget,BooleanSupplier cancelled) {
        long start=System.nanoTime(),storage=0;int bytes=0,actions=0,work=0;boolean finish=false;GlTransferState state=null;
        Throwable failure=null;
        try {
            steps:while(work<budget.workUnits()&&(work==0||System.nanoTime()-start<budget.nanoseconds())) {
                check(cancelled);boolean gpu=phase==Phase.STORAGE||phase==Phase.ROWS;if(gpu&&actions>=budget.actions())break;
                if(gpu&&state==null) { state=new GlTransferState(device);GlTransferState.unpack();glActiveTexture(GL_TEXTURE0);glBindSampler(0,0); }
                switch(phase) {
                    case STORAGE -> {
                        device.beginStorage(allocation);allocation.textures[0]=glGenTextures();glBindTexture(GL_TEXTURE_2D,allocation.textures[0]);
                        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_BASE_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);
                        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,0L);device.check("map texture storage");storage+=totalBytes;phase=Phase.ROWS;
                    }
                    case ROWS -> {
                        int rowBytes=width*4,capacity=Math.min(65536,Math.min(device.limits().stagingBytes(),budget.transferBytes()-bytes)),rows=Math.min(height-row,capacity/rowBytes);if(rows==0)break steps;
                        int count=rows*rowBytes;var stage=device.staging();stage.limit(count);stage.put(raster.image().orElseThrow().pixels().slice(row*rowBytes,count));stage.flip();glBindTexture(GL_TEXTURE_2D,allocation.textures[0]);glTexSubImage2D(GL_TEXTURE_2D,0,0,row,width,rows,GL_RGBA,GL_UNSIGNED_BYTE,stage);device.check("bounded map texture rows");row+=rows;uploaded+=count;bytes+=count;if(row==height)phase=Phase.FINISHING;
                    }
                    case FINISHING -> { if(row!=height||uploaded!=totalBytes)throw new GlFailure(GlFailure.Code.STATE,"Map upload does not cover its complete raster");device.check("complete map upload");finish=true; }
                    default -> throw new GlFailure(GlFailure.Code.STATE,"Unexpected map upload phase");
                }
                work++;if(gpu)actions++;if(finish)break;
            }
        }catch(RuntimeException|Error error) { failure=error;throw error; }
        finally {
            if(state!=null)try { state.close(); }
            catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        }
        if(finish) { check(cancelled);completed=new GlMap(device,allocation,width,height);raster=null;phase=Phase.READY; }
        return stepResult(start,bytes,storage,actions,work);
    }
    private Step stepResult(long start,int bytes,long storage,int actions,int work) { return new Step(progress(),bytes,storage,actions,work,System.nanoTime()-start); }
    public GlMap take() { device.current();if(!ready()||completed==null)throw new GlFailure(GlFailure.Code.STATE,"No complete GPU map is available");var result=completed;completed=null;allocation=null;phase=Phase.TAKEN;return result; }
    private void dispose(Phase terminal) { try { if(allocation!=null) { if(allocation.unstartedStorage)device.discardUnstarted(allocation);else device.retire(allocation); } }finally { if(allocation==null||allocation.retired||allocation.released) { allocation=null;raster=null;completed=null; }phase=terminal; } }
    @Override public void close() { if(phase==Phase.TAKEN||phase==Phase.CLOSED&&allocation==null)return;dispose(Phase.CLOSED); }
}
