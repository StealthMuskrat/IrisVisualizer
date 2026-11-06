package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Separate immutable geometry/color buffers: twelve GPU bytes per rectangle, bounded CPU upload or GPU geometry copy. */
public final class GlNoiseUpload implements AutoCloseable {
    public static final int GEOMETRY_BYTES=8,COLOR_BYTES=4,INSTANCE_BYTES=GEOMETRY_BYTES+COLOR_BYTES;
    public enum Phase { STORAGE, GEOMETRY, FACES, LAYOUT, FINISHING, READY, TAKEN, CANCELLED, FAILED, CLOSED }
    public record Progress(Phase phase,int completedFaces,int totalFaces,long uploadedBytes,long totalBytes,long copiedBytes,long totalCopyBytes) {
        public Progress(Phase phase,int completedFaces,int totalFaces,long uploadedBytes,long totalBytes) { this(phase,completedFaces,totalFaces,uploadedBytes,totalBytes,0,0); }
    }
    public record Step(Progress progress,int transferBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds,int copiedBytes) {
        public Step(Progress progress,int transferBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds) { this(progress,transferBytes,storageBytes,actions,workUnits,elapsedNanoseconds,0); }
    }
    private final GlDevice device;private final int count;private GlDevice.Allocation allocation;private GlNoiseScene completed;private Phase phase=Phase.STORAGE;private int geometryFace,face;private GlNoiseScene geometrySource;private final boolean copiedGeometry;
    private GlNoiseUpload(GlDevice device,GlDevice.Allocation allocation,int count,GlNoiseScene geometrySource) { this.device=device;this.allocation=allocation;this.count=count;this.geometrySource=geometrySource;copiedGeometry=geometrySource!=null; }
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
        try { allocation=device.reserve(0,surface.payloadBytes(),0,0,0,0,0,0,0,null);allocation.retained=surface;return new GlNoiseUpload(device,allocation,surface.size(),null); }
        catch(RuntimeException|Error failure) { if(allocation!=null)try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    /** Borrowed source; retain its complete GPU owner through the last queued copy and source release. */
    public static GlNoiseUpload recolor(GlNoiseScene source,NoiseSurface surface) {
        Objects.requireNonNull(source);Objects.requireNonNull(surface);var device=source.device;var original=source.surface();validate(device,surface);
        if(!original.sharesGeometry(surface))throw new GlFailure(GlFailure.Code.STALE,"Scalar GPU recoloring requires the exact shared immutable geometry");
        GlDevice.Allocation allocation=null;GlNoiseScene owner=null;
        try {
            allocation=device.reserve(0,surface.payloadBytes(),0,0,0,0,0,0,0,null);allocation.retained=surface;owner=source.retain();return new GlNoiseUpload(device,allocation,surface.size(),owner);
        }catch(RuntimeException|Error failure) {
            if(owner!=null)try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(allocation!=null)try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    /** Consumes a fresh recolored CPU surface through destination admission, including rejection. */
    public static GlNoiseUpload recolor(GlNoiseScene source,RenderInput<NoiseSurface> input) {
        Objects.requireNonNull(input);GlNoiseUpload result=null;
        try(input;var owner=input.retain()) {
            Objects.requireNonNull(source);var memory=source.device.memory().orElse(null);
            if(owner.admission().tracked()&&(memory==null||!owner.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar GPU recoloring requires the same shared coordinator");
            result=recolor(source,owner.value());
        }catch(RuntimeException|Error failure) { if(result!=null)try { result.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
        return result;
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
    public Progress progress() { return new Progress(phase,Math.min(geometryFace,face),count,(copiedGeometry?0:(long)geometryFace*GEOMETRY_BYTES)+(long)face*COLOR_BYTES,(long)count*(copiedGeometry?COLOR_BYTES:INSTANCE_BYTES),copiedGeometry?(long)geometryFace*GEOMETRY_BYTES:0,copiedGeometry?(long)count*GEOMETRY_BYTES:0); }
    public boolean ready() { return phase==Phase.READY; }
    boolean ownsResources() { return allocation!=null||geometrySource!=null; }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Scalar upload is obsolete"); }
    public Step step(GlUploadBudget budget,BooleanSupplier cancelled) {
        Objects.requireNonNull(budget);Objects.requireNonNull(cancelled);device.current();if(ready())return new Step(progress(),0,0,0,0,0);
        if(allocation==null||phase==Phase.FAILED||phase==Phase.CANCELLED||phase==Phase.CLOSED)throw new GlFailure(GlFailure.Code.STATE,"Scalar upload is no longer resumable");
        try { return advance(budget,cancelled); }catch(RuntimeException|Error failure) { try { dispose(failure instanceof CancellationException?Phase.CANCELLED:Phase.FAILED); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    private Step advance(GlUploadBudget budget,BooleanSupplier cancelled) {
        long start=System.nanoTime(),storage=0;int bytes=0,copied=0,actions=0,units=0;boolean finish=false;GlTransferState state=null;
        Throwable failure=null;
        try {
            device.live(allocation);
            steps:while(units<budget.workUnits()&&(units==0||System.nanoTime()-start<budget.nanoseconds())) {
                check(cancelled);boolean gpu=count>0&&phase!=Phase.FINISHING;if(gpu&&actions>=budget.actions())break;
                if(gpu&&state==null)state=new GlTransferState(device);
                switch(phase) {
                    case STORAGE -> {
                        device.expand(allocation,(long)count*INSTANCE_BYTES,count==0?0:2,0,count==0?0:1);
                        if(count>0) {
                            allocation.buffers[0]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[0]);glBufferData(GL_ARRAY_BUFFER,(long)count*GEOMETRY_BYTES,GL_STATIC_DRAW);
                            allocation.buffers[1]=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[1]);glBufferData(GL_ARRAY_BUFFER,(long)count*COLOR_BYTES,GL_STATIC_DRAW);device.check("scalar geometry/color storage");storage=(long)count*INSTANCE_BYTES;
                        }phase=count==0?Phase.FINISHING:Phase.GEOMETRY;
                    }
                    case GEOMETRY -> {
                        int capacity=Math.min(65536,Math.min(device.limits().stagingBytes(),budget.transferBytes()-bytes-copied)),n=Math.min(count-geometryFace,capacity/GEOMETRY_BYTES);if(n==0)break steps;
                        if(copiedGeometry) {
                            device.live(geometrySource.allocation);glBindBuffer(GL_COPY_READ_BUFFER,geometrySource.allocation.buffers[0]);glBindBuffer(GL_COPY_WRITE_BUFFER,allocation.buffers[0]);
                            glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,(long)geometryFace*GEOMETRY_BYTES,(long)geometryFace*GEOMETRY_BYTES,(long)n*GEOMETRY_BYTES);device.check("bounded scalar geometry copy");copied+=n*GEOMETRY_BYTES;
                        }else {
                            var surface=(NoiseSurface)allocation.retained;var stage=device.staging();stage.limit(n*GEOMETRY_BYTES);
                            for(int i=0;i<n;i++) { if((i&255)==0)check(cancelled);stage.putInt((surface.cell(geometryFace+i)<<3)|surface.direction(geometryFace+i).ordinal());stage.putInt(surface.spanWord(geometryFace+i)); }
                            stage.flip();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[0]);glBufferSubData(GL_ARRAY_BUFFER,(long)geometryFace*GEOMETRY_BYTES,stage);device.check("bounded scalar geometry upload");bytes+=n*GEOMETRY_BYTES;
                        }
                        geometryFace+=n;if(geometryFace==count)phase=Phase.FACES;
                    }
                    case FACES -> {
                        int capacity=Math.min(65536,Math.min(device.limits().stagingBytes(),budget.transferBytes()-bytes-copied)),n=Math.min(count-face,capacity/COLOR_BYTES);if(n==0)break steps;
                        var surface=(NoiseSurface)allocation.retained;var stage=device.staging();stage.limit(n*COLOR_BYTES);
                        for(int i=0;i<n;i++) { if((i&255)==0)check(cancelled);stage.putInt(surface.rgba(face+i)); }
                        stage.flip();glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[1]);glBufferSubData(GL_ARRAY_BUFFER,(long)face*COLOR_BYTES,stage);device.check("bounded scalar color upload");face+=n;bytes+=n*COLOR_BYTES;if(face==count)phase=Phase.LAYOUT;
                    }
                    case LAYOUT -> {
                        allocation.vaos[0]=glGenVertexArrays();glBindVertexArray(allocation.vaos[0]);bindInstances(allocation,0);
                        glEnableVertexAttribArray(0);glVertexAttribDivisor(0,1);glEnableVertexAttribArray(1);glVertexAttribDivisor(1,1);device.check("scalar geometry/color layout");phase=Phase.FINISHING;
                    }
                    case FINISHING -> { if(face!=count||geometryFace!=count)throw new GlFailure(GlFailure.Code.STATE,"Scalar upload is incomplete");device.check("complete scalar upload");finish=true; }
                    default -> throw new GlFailure(GlFailure.Code.STATE,"Unexpected scalar upload phase");
                }
                units++;if(gpu)actions++;if(finish)break;
            }
        }catch(RuntimeException|Error error) { failure=error;throw error; }
        finally {
            if(state!=null)try { state.close(); }
            catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        }
        if(finish) { check(cancelled);releaseSource();check(cancelled);completed=new GlNoiseScene(device,allocation);phase=Phase.READY; }
        return new Step(progress(),bytes,storage,actions,units,System.nanoTime()-start,copied);
    }
    public GlNoiseScene take() { device.current();if(!ready()||completed==null)throw new GlFailure(GlFailure.Code.STATE,"No complete scalar scene is available");var result=completed;completed=null;allocation=null;phase=Phase.TAKEN;return result; }
    static void bindInstances(GlDevice.Allocation allocation,int first) {
        glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[0]);glVertexAttribIPointer(0,2,GL_UNSIGNED_INT,GEOMETRY_BYTES,(long)first*GEOMETRY_BYTES);
        glBindBuffer(GL_ARRAY_BUFFER,allocation.buffers[1]);glVertexAttribIPointer(1,1,GL_UNSIGNED_INT,COLOR_BYTES,(long)first*COLOR_BYTES);
    }
    private void releaseSource() { if(geometrySource!=null)try { geometrySource.close();geometrySource=null; }catch(RuntimeException|Error failure) { if(geometrySource.closed())geometrySource=null;throw failure; } }
    private void dispose(Phase terminal) {
        Throwable failure=null;
        try { if(allocation!=null)device.retire(allocation); }catch(RuntimeException|Error error) { failure=error; }
        finally { if(allocation==null||allocation.retired||allocation.released) { allocation=null;completed=null; }phase=terminal; }
        try { releaseSource(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    @Override public void close() { if((phase==Phase.TAKEN||phase==Phase.CLOSED)&&!ownsResources())return;dispose(Phase.CLOSED); }
}
