package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Twelve GPU bytes per rectangle; bounded staging writes instances without a second full CPU mesh. */
public final class GlNoiseUpload implements AutoCloseable {
    public static final int INSTANCE_BYTES=12;
    public enum Phase { STORAGE, FACES, LAYOUT, FINISHING, READY, TAKEN, CANCELLED, FAILED, CLOSED }
    public record Progress(Phase phase,int completedFaces,int totalFaces,long uploadedBytes,long totalBytes) {}
    public record Step(Progress progress,int transferBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds) {}
    private final GlDevice device;private final int count;private GlDevice.Allocation allocation;private GlNoiseScene completed;private Phase phase=Phase.STORAGE;private int face;
    private GlNoiseUpload(GlDevice device,GlDevice.Allocation allocation,int count) { this.device=device;this.allocation=allocation;this.count=count; }
    /** Grid-only precision bounds, available before scalar meshing or recoloring. */
    static void precision(NoiseVolume.Grid g) {
        Objects.requireNonNull(g);float scale=(float)g.horizontal().blocksPerSample();
        if(!Float.isFinite(scale)||scale<Float.MIN_NORMAL||Math.max((double)g.width()*scale,(double)g.depth()*scale)>1048576)throw new GlFailure(GlFailure.Code.LIMIT,"Scalar bins exceed supported float scale/relative-span bounds; data was not reduced");
    }
    /** Borrowed completeness/context/precision checks; no journal or source owner is admitted. */
    static void validate(GlDevice device,NoiseSurface surface) {
        Objects.requireNonNull(device);Objects.requireNonNull(surface);device.current();if(surface.requiresPlaceholder())throw new GlFailure(GlFailure.Code.STATE,"A complete scalar boundary surface is required");precision(surface.volume().grid());
    }
    public static GlNoiseUpload begin(GlDevice device,NoiseSurface surface) {
        validate(device,surface);
        GlDevice.Allocation allocation=null;
        try { allocation=device.reserve(0,surface.payloadBytes(),0,0,0,0,0,0,0,null);allocation.retained=surface;return new GlNoiseUpload(device,allocation,surface.size()); }
        catch(RuntimeException|Error failure) { if(allocation!=null)try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    /** Source admission remains live until the destination device journal is admitted; consumes every outcome. */
    public static GlNoiseUpload begin(GlDevice device,RenderInput<NoiseSurface> input) {
        Objects.requireNonNull(input);GlNoiseUpload result=null;
        try(input;var source=input.retain()) {
            Objects.requireNonNull(device);var memory=device.memory().orElse(null);
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar upload requires the same shared coordinator");result=begin(device,source.value());
        }catch(RuntimeException|Error failure) {
            if(result!=null)try { result.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            throw failure;
        }
        return result;
    }
    public Progress progress() { return new Progress(phase,face,count,(long)face*INSTANCE_BYTES,(long)count*INSTANCE_BYTES); }
    public boolean ready() { return phase==Phase.READY; }
    boolean ownsResources() { return allocation!=null; }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Scalar upload is obsolete"); }
    public Step step(GlUploadBudget budget,BooleanSupplier cancelled) {
        Objects.requireNonNull(budget);Objects.requireNonNull(cancelled);device.current();if(ready())return new Step(progress(),0,0,0,0,0);
        if(allocation==null||phase==Phase.FAILED||phase==Phase.CANCELLED||phase==Phase.CLOSED)throw new GlFailure(GlFailure.Code.STATE,"Scalar upload is no longer resumable");
        try { return advance(budget,cancelled); }catch(RuntimeException|Error failure) { try { dispose(failure instanceof CancellationException?Phase.CANCELLED:Phase.FAILED); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    private Step advance(GlUploadBudget budget,BooleanSupplier cancelled) {
        long start=System.nanoTime(),storage=0;int bytes=0,actions=0,units=0;boolean finish=false;GlTransferState state=null;
        Throwable failure=null;
        try {
            device.live(allocation);
            steps:while(units<budget.workUnits()&&(units==0||System.nanoTime()-start<budget.nanoseconds())) {
                check(cancelled);boolean gpu=count>0&&phase!=Phase.FINISHING;if(gpu&&actions>=budget.actions())break;
                if(gpu&&state==null)state=new GlTransferState(device);
                switch(phase) {
                    case STORAGE -> {
                        device.expand(allocation,(long)count*INSTANCE_BYTES,count==0?0:1,0,count==0?0:1);
                        if(count>0) { allocation.buffers[0]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[0]);glBufferData(GL_ARRAY_BUFFER,(long)count*INSTANCE_BYTES,GL_STATIC_DRAW);device.check("scalar instance storage");storage=(long)count*INSTANCE_BYTES; }phase=count==0?Phase.FINISHING:Phase.FACES;
                    }
                    case FACES -> {
                        int capacity=Math.min(65536,Math.min(device.limits().stagingBytes(),budget.transferBytes()-bytes)),n=Math.min(count-face,capacity/INSTANCE_BYTES);if(n==0)break steps;
                        var surface=(NoiseSurface)allocation.retained;var stage=device.staging();stage.limit(n*INSTANCE_BYTES);
                        for(int i=0;i<n;i++) { if((i&255)==0)check(cancelled);stage.putInt((surface.cell(face+i)<<3)|surface.direction(face+i).ordinal());stage.putInt(surface.rgba(face+i));stage.putInt(surface.spanWord(face+i)); }
                        stage.flip();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[0]);glBufferSubData(GL_ARRAY_BUFFER,(long)face*INSTANCE_BYTES,stage);device.check("bounded scalar instance data");face+=n;bytes+=n*INSTANCE_BYTES;if(face==count)phase=Phase.LAYOUT;
                    }
                    case LAYOUT -> { allocation.vaos[0]=glGenVertexArrays();glBindVertexArray(allocation.vaos[0]);glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[0]);glVertexAttribIPointer(0,3,GL_UNSIGNED_INT,INSTANCE_BYTES,0L);glEnableVertexAttribArray(0);glVertexAttribDivisor(0,1);device.check("scalar instance layout");phase=Phase.FINISHING; }
                    case FINISHING -> { if(face!=count)throw new GlFailure(GlFailure.Code.STATE,"Scalar upload is incomplete");device.check("complete scalar upload");finish=true; }
                    default -> throw new GlFailure(GlFailure.Code.STATE,"Unexpected scalar upload phase");
                }
                units++;if(gpu)actions++;if(finish)break;
            }
        }catch(RuntimeException|Error error) { failure=error;throw error; }
        finally {
            if(state!=null)try { state.close(); }
            catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        }
        if(finish) { check(cancelled);completed=new GlNoiseScene(device,allocation);phase=Phase.READY; }
        return new Step(progress(),bytes,storage,actions,units,System.nanoTime()-start);
    }
    public GlNoiseScene take() { device.current();if(!ready()||completed==null)throw new GlFailure(GlFailure.Code.STATE,"No complete scalar scene is available");var result=completed;completed=null;allocation=null;phase=Phase.TAKEN;return result; }
    private void dispose(Phase terminal) { try { if(allocation!=null)device.retire(allocation); }finally { if(allocation==null||allocation.retired||allocation.released) { allocation=null;completed=null; }phase=terminal; } }
    @Override public void close() { if((phase==Phase.TAKEN||phase==Phase.CLOSED)&&allocation==null)return;dispose(Phase.CLOSED); }
}
