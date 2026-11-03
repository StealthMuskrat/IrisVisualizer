package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.RgbaImage;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Bounded asynchronous readback of an immutable completed image. No source pixels are written. */
public final class GlReadback implements AutoCloseable {
    public record Limits(long pixelBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(64L*1024*1024,128L*1024*1024);
        public Limits { if(pixelBytes<0||pixelBytes>64L*1024*1024||workingBytes<0||workingBytes>128L*1024*1024)throw new IllegalArgumentException("Readback limits exceed their bounded payload"); }
    }
    /** Detached top-left RGBA pixels and the exact original image receipt; no GL/native owner remains. */
    public record Pixels(Optional<GlFrame.Info> world,Optional<GlMapFrame.Info> map,Optional<GlNoiseFrame.Info> noise,RgbaImage image) {
        public Pixels(Optional<GlFrame.Info> world,Optional<GlMapFrame.Info> map,RgbaImage image) { this(world,map,Optional.empty(),image); }
        public Pixels {
            Objects.requireNonNull(world);Objects.requireNonNull(map);Objects.requireNonNull(noise);Objects.requireNonNull(image);if((world.isPresent()?1:0)+(map.isPresent()?1:0)+(noise.isPresent()?1:0)!=1)throw new IllegalArgumentException("Readback requires exactly one completed image receipt");
            int width=world.isPresent()?world.orElseThrow().width():map.isPresent()?map.orElseThrow().width():noise.orElseThrow().width(),height=world.isPresent()?world.orElseThrow().height():map.isPresent()?map.orElseThrow().height():noise.orElseThrow().height();
            if(image.width()!=width||image.height()!=height)throw new IllegalArgumentException("Readback dimensions differ from their image receipt");
        }
    }
    public enum Phase { STORAGE, READING, FENCING, WAITING, COPYING, FINISHING, READY, TAKEN, CANCELLED, FAILED, CLOSED }
    public record Progress(Phase phase,int gpuRows,int cpuRows,int totalRows,long readBytes,long copiedBytes,long totalBytes) {}
    public record Step(Progress progress,int readBytes,int copiedBytes,long storageBytes,int actions,int workUnits,long elapsedNanoseconds) {}
    private interface Source extends AutoCloseable {
        GlTarget target();GlDevice.Allocation journal();Optional<GlFrame.Info> world();Optional<GlMapFrame.Info> map();boolean closed();void close();
        default Optional<GlNoiseFrame.Info> noise() { return Optional.empty(); }
    }
    private record WorldSource(GlFrame frame) implements Source {
        public GlTarget target() { return frame.readbackTarget(); }public GlDevice.Allocation journal() { return frame.readbackJournal(); }
        public Optional<GlFrame.Info> world() { return Optional.of(frame.info()); }public Optional<GlMapFrame.Info> map() { return Optional.empty(); }
        public boolean closed() { return frame.closed(); }public void close() { frame.close(); }
    }
    private record MapSource(GlMapFrame frame) implements Source {
        public GlTarget target() { return frame.readbackTarget(); }public GlDevice.Allocation journal() { return frame.readbackJournal(); }
        public Optional<GlFrame.Info> world() { return Optional.empty(); }public Optional<GlMapFrame.Info> map() { return Optional.of(frame.info()); }
        public boolean closed() { return frame.closed(); }public void close() { frame.close(); }
    }
    private record NoiseSource(GlNoiseFrame frame) implements Source {
        public GlTarget target() { return frame.readbackTarget(); }public GlDevice.Allocation journal() { return frame.readbackJournal(); }
        public Optional<GlFrame.Info> world() { return Optional.empty(); }public Optional<GlMapFrame.Info> map() { return Optional.empty(); }public Optional<GlNoiseFrame.Info> noise() { return Optional.of(frame.info()); }
        public boolean closed() { return frame.closed(); }public void close() { frame.close(); }
    }
    private final GlDevice device;private final GlTarget target;private final int width,height,rowBytes;private final long bytes;private final Optional<GlFrame.Info> world;private final Optional<GlMapFrame.Info> map;private final Optional<GlNoiseFrame.Info> noise;
    private Source source;private GlDevice.Allocation work,output;private RgbaImage.Builder builder;private Pixels completed;private Phase phase=Phase.STORAGE;private int gpuRows,cpuRows;
    private GlReadback(Source source,GlDevice.Allocation work,GlDevice.Allocation output,RgbaImage.Builder builder) {
        this.source=source;target=source.target();device=target.device;width=target.width();height=target.height();rowBytes=width*4;bytes=(long)rowBytes*height;world=source.world();map=source.map();noise=source.noise();this.work=work;this.output=output;this.builder=builder;
    }
    public static GlReadback begin(GlFrame frame) { return begin(frame,Limits.DEFAULT); }
    public static GlReadback begin(GlMapFrame frame) { return begin(frame,Limits.DEFAULT); }
    public static GlReadback begin(GlNoiseFrame frame) { return begin(frame,Limits.DEFAULT); }
    public static GlReadback begin(GlNoiseFrame frame,Limits limits) {
        Objects.requireNonNull(frame);Objects.requireNonNull(limits);var owner=frame.retain();
        try { return begin(new NoiseSource(owner),limits); }catch(RuntimeException|Error failure) { try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    public static GlReadback begin(GlFrame frame,Limits limits) {
        Objects.requireNonNull(frame);Objects.requireNonNull(limits);var owner=frame.retain();
        try { return begin(new WorldSource(owner),limits); }catch(RuntimeException|Error failure) { try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    public static GlReadback begin(GlMapFrame frame,Limits limits) {
        Objects.requireNonNull(frame);Objects.requireNonNull(limits);var owner=frame.retain();
        try { return begin(new MapSource(owner),limits); }catch(RuntimeException|Error failure) { try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    private static GlReadback begin(Source source,Limits limits) {
        var target=source.target();var device=target.device;if(!target.frameComplete())throw new GlFailure(GlFailure.Code.STATE,"Only complete immutable images may be read back");long bytes=(long)target.width()*target.height()*4;
        if(bytes>limits.pixelBytes()||bytes*2>limits.workingBytes()||target.width()*4>device.limits().stagingBytes())throw new GlFailure(GlFailure.Code.LIMIT,"Readback buffer/output or row-staging budget exceeded");
        GlDevice.Allocation work=null,output=null;
        try {
            work=device.reserve(0,bytes,0,0,0,0,0,0,0,null);device.depend(work,source.journal());output=device.reserve(0,0,0,0,0,0,0,0,0,null);
            var builder=RgbaImage.builder(target.width(),target.height(),limits.pixelBytes());work.retained=builder;return new GlReadback(source,work,output,builder);
        }catch(RuntimeException|Error failure) {
            if(output!=null)try { device.retire(output); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(work!=null)try { device.retire(work); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    public Progress progress() { return new Progress(phase,gpuRows,cpuRows,height,(long)gpuRows*rowBytes,(long)cpuRows*rowBytes,bytes); }
    GlDevice device() { return device; }
    public boolean ready() { return phase==Phase.READY; }
    boolean ownsResources() { return work!=null||output!=null||source!=null; }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Image readback cancelled"); }
    public Step step(GlUploadBudget budget,BooleanSupplier cancelled) {
        Objects.requireNonNull(budget);Objects.requireNonNull(cancelled);device.current();if(ready())return new Step(progress(),0,0,0,0,0,0);
        if(work==null||phase==Phase.FAILED||phase==Phase.CANCELLED||phase==Phase.CLOSED)throw new GlFailure(GlFailure.Code.STATE,"Readback is no longer resumable");
        try { return advance(budget,cancelled); }
        catch(RuntimeException|Error failure) { try { dispose(failure instanceof CancellationException?Phase.CANCELLED:Phase.FAILED); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    private Step advance(GlUploadBudget budget,BooleanSupplier cancelled) {
        long start=System.nanoTime(),storage=0;int readNow=0,copiedNow=0,actions=0,units=0;boolean finish=false;GlPackState state=null;
        try {
            device.live(work);device.live(output);device.live(target.allocation);
            steps:while(units<budget.workUnits()&&(units==0||System.nanoTime()-start<budget.nanoseconds())) {
                check(cancelled);boolean action=phase!=Phase.FINISHING;if(action&&actions>=budget.actions())break;
                boolean binds=phase==Phase.STORAGE||phase==Phase.READING||phase==Phase.COPYING;
                if(binds&&state==null) { state=new GlPackState(device);state.tightlyPacked(); }
                switch(phase) {
                    case STORAGE -> {
                        device.expand(work,bytes,1,0,0);work.buffers[0]=glGenBuffers();glBindBuffer(GL_PIXEL_PACK_BUFFER,work.buffers[0]);glBufferData(GL_PIXEL_PACK_BUFFER,bytes,GL_STREAM_READ);device.check("readback pixel buffer storage");storage+=bytes;phase=Phase.READING;
                    }
                    case READING -> {
                        int capacity=Math.min(65536,budget.transferBytes()-readNow-copiedNow),rows=Math.min(height-gpuRows,capacity/rowBytes);if(rows==0)break steps;
                        glBindFramebuffer(GL_READ_FRAMEBUFFER,target.allocation.framebuffers[0]);glReadBuffer(GL_COLOR_ATTACHMENT0);glBindBuffer(GL_PIXEL_PACK_BUFFER,work.buffers[0]);
                        glReadPixels(0,gpuRows,width,rows,GL_RGBA,GL_UNSIGNED_BYTE,(long)gpuRows*rowBytes);device.check("bounded completed-image read rows");gpuRows+=rows;readNow+=rows*rowBytes;if(gpuRows==height)phase=Phase.FENCING;
                    }
                    case FENCING -> {
                        work.transferFence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(work.transferFence==0)throw new GlFailure(GlFailure.Code.GL_ERROR,"Could not create image readback fence");device.check("readback completion fence");glFlush();device.check("readback command flush");phase=Phase.WAITING;
                    }
                    case WAITING -> {
                        int signal=glClientWaitSync(work.transferFence,0,0L);device.check("nonblocking readback fence poll");
                        if(signal==GL_TIMEOUT_EXPIRED) { units++;actions++;break steps; }
                        if(signal!=GL_ALREADY_SIGNALED&&signal!=GL_CONDITION_SATISFIED)throw new GlFailure(GlFailure.Code.GL_ERROR,"Readback completion fence could not be observed");phase=Phase.COPYING;
                    }
                    case COPYING -> {
                        int capacity=Math.min(65536,Math.min(device.limits().stagingBytes(),budget.transferBytes()-readNow-copiedNow)),rows=Math.min(height-cpuRows,capacity/rowBytes);if(rows==0)break steps;
                        var stage=device.staging();stage.limit(rows*rowBytes);glBindBuffer(GL_PIXEL_PACK_BUFFER,work.buffers[0]);glGetBufferSubData(GL_PIXEL_PACK_BUFFER,(long)(height-cpuRows-rows)*rowBytes,stage);device.check("bounded readback CPU rows");
                        builder.appendRows(stage,rows,true);cpuRows+=rows;copiedNow+=rows*rowBytes;if(cpuRows==height)phase=Phase.FINISHING;
                    }
                    case FINISHING -> { if(gpuRows!=height||cpuRows!=height||builder.completedRows()!=height)throw new GlFailure(GlFailure.Code.STATE,"Readback does not cover the complete image");device.check("complete image readback");finish=true; }
                    default -> throw new GlFailure(GlFailure.Code.STATE,"Unexpected readback phase");
                }
                units++;if(action)actions++;if(finish)break;
            }
        }finally { if(state!=null)state.close(); }
        if(finish) {
            check(cancelled);var image=builder.finish();var result=new Pixels(world,map,noise,image);device.moveCpu(work,output,bytes,image);releaseWork();check(cancelled);completed=result;phase=Phase.READY;
        }
        return new Step(progress(),readNow,copiedNow,storage,actions,units,System.nanoTime()-start);
    }
    Pixels readyPixels() { device.current();if(!ready()||completed==null)throw new GlFailure(GlFailure.Code.STATE,"No complete readback is available");return completed; }
    /** Transfers immutable CPU data once. Its memory is caller-owned after this operation. */
    public Pixels take() {
        var result=readyPixels();device.retire(output);output=null;completed=null;phase=Phase.TAKEN;return result;
    }
    private void releaseWork() {
        Throwable failure=null;
        if(work!=null)try { device.retire(work);work=null; }catch(RuntimeException|Error error) { if(work.retired||work.released)work=null;failure=error; }
        if(source!=null)try { source.close();source=null; }catch(RuntimeException|Error error) { if(source.closed())source=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        builder=null;if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    private void dispose(Phase terminal) {
        Throwable failure=null;try { releaseWork(); }catch(RuntimeException|Error error) { failure=error; }
        if(output!=null)try { device.retire(output);output=null; }catch(RuntimeException|Error error) { if(output.retired||output.released)output=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        completed=null;phase=terminal;if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    @Override public void close() { if((phase==Phase.TAKEN||phase==Phase.CLOSED)&&!ownsResources())return;dispose(Phase.CLOSED); }
}
