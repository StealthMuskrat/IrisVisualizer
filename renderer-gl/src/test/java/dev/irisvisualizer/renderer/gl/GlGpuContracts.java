package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import java.util.zip.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Explicit Windows GPU stage contracts. Compilation never creates a context or runs these assertions. */
public final class GlGpuContracts {
    private static final BooleanSupplier LIVE=()->false;
    private static final GlUploadBudget TINY=new GlUploadBudget(65536,1,1,1);
    private static final int[] COLORS={0xff0000ff,0x00ff0080,0x0000ff00,0x7f3fbfff};
    private static final List<String> passed=new ArrayList<>();
    private static GlDevice.Driver driver;
    private GlGpuContracts() {}
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void equal(Object expected,Object actual,String message) { check(Objects.equals(expected,actual),message+": expected "+expected+", got "+actual); }
    private static <T extends Throwable> T throwsType(Class<T> type,Runnable action,String message) {
        try { action.run(); }catch(Throwable failure) { if(type.isInstance(failure))return type.cast(failure);throw new AssertionError(message+": wrong exception",failure); }
        throw new AssertionError(message+": expected exception missing");
    }
    private static void failure(GlFailure.Code code,Runnable action) { equal(code,throwsType(GlFailure.class,action,"GL boundary rejection").code(),"structured GL failure"); }
    private static void until(BooleanSupplier ready,Runnable step) {
        long start=System.nanoTime();int steps=0;
        while(!ready.getAsBoolean()) { check(++steps<1_000_000&&System.nanoTime()-start<20_000_000_000L,"bounded GPU progress deadline");step.run();Thread.yield(); }
    }
    private static void budget(int bytes,int actions,int work) { check(bytes>=0&&bytes<=TINY.transferBytes()&&actions>=0&&actions<=1&&work>=0&&work<=1,"hard per-step transfer/action/work bounds"); }
    private static void withDevice(Consumer<GlDevice> action) {
        try(var memory=new RenderMemory(new RenderMemory.Limits(64L*1024*1024,32))) {
            var device=new GlDevice(new GlDevice.Limits(16L*1024*1024,32L*1024*1024,65536,64,4096,64,4096),memory);
            try(device) {
                if(driver==null)driver=device.driver();else equal(driver,device.driver(),"same current context driver provenance");
                action.accept(device);equal(GL_NO_ERROR,glGetError(),"GPU contract leaves no GL error");
            }
            equal(driver,device.driver(),"driver receipt survives device closure");equal(0L,memory.stats().chargedBytes(),"device closure releases all known payload");equal(0,memory.stats().accounts(),"device closure releases admission accounts");
        }
    }
    private static void group(String name,Consumer<GlDevice> action) { withDevice(action);passed.add(name);System.out.println("PASS "+name); }
    private static void chunk(DataOutputStream out,String name,byte[] bytes) throws IOException {
        byte[] type=name.getBytes(StandardCharsets.US_ASCII);var crc=new CRC32();crc.update(type);crc.update(bytes);out.writeInt(bytes.length);out.write(type);out.write(bytes);out.writeInt((int)crc.getValue());
    }
    private static SourceImage image() {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeLong(0x89504e470d0a1a0aL);
            var headerBytes=new ByteArrayOutputStream();var header=new DataOutputStream(headerBytes);header.writeInt(2);header.writeInt(2);header.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",headerBytes.toByteArray());
            var compressed=new ByteArrayOutputStream();try(var zlib=new DeflaterOutputStream(compressed)) { for(int y=0;y<2;y++) { zlib.write(0);for(int x=0;x<2;x++)for(int shift=24;shift>=0;shift-=8)zlib.write(COLORS[y*2+x]>>>shift&255); } }
            chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);var source=SourceImage.decode(SourceImage.Encoded.capture(new SourceImage.Identity("fixtures/gpu.png",1),bytes.toByteArray()),LIVE);check(!source.requiresPlaceholder(),"synthetic PNG fixture decodes");return source;
        }catch(IOException impossible) { throw new AssertionError(impossible); }
    }
    private static MapSamples.Context context() { return new MapSamples.Context(new WorldSnapshot.Source(UUID.randomUUID(),"0".repeat(64),"synthetic GPU contract",BlockModels.PROFILE,AssetId.minecraft("overworld"),Long.MAX_VALUE),"noise","fixture","supplied original values; no native generator"); }
    private static MapRaster raster() {
        var source=MapSamples.noise(context(),new MapSamples.Grid(4,-8,2,2,2),new double[]{0,1,0.5,123},new byte[]{1,1,1,0},LIVE);
        var result=MapRaster.render(source,new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE),LIVE);check(!result.requiresPlaceholder(),"complete synthetic scalar raster");return result;
    }
    private static GlImage upload(GlDevice device,SourceImage source) {
        try(var job=GlImageUpload.begin(device,source)) {
            throwsType(GlFailure.class,job::take,"incomplete GPU image cannot escape");
            until(job::ready,()-> { var step=job.step(TINY,LIVE);budget(step.transferBytes(),step.actions(),step.workUnits()); });
            equal(2,job.progress().completedRows(),"all original image rows uploaded");equal(16L,job.progress().uploadedBytes(),"all original image bytes uploaded");return job.take();
        }
    }
    private static GlMap upload(GlDevice device,MapRaster source) {
        try(var job=GlMapUpload.begin(device,source)) {
            until(job::ready,()-> { var step=job.step(TINY,LIVE);budget(step.transferBytes(),step.actions(),step.workUnits()); });return job.take();
        }
    }
    private static GlNoiseScene upload(GlDevice device,NoiseSurface source) {
        try(var job=GlNoiseUpload.begin(device,source)) {
            until(job::ready,()-> { var step=job.step(TINY,LIVE);budget(step.transferBytes(),step.actions(),step.workUnits()); });equal(source.size(),job.progress().completedFaces(),"all scalar instances uploaded");return job.take();
        }
    }
    private static GlReadback.Pixels read(GlReadback job) {
        until(job::ready,()-> { var step=job.step(TINY,LIVE);budget(step.readBytes()+step.copiedBytes(),step.actions(),step.workUnits()); });return job.take();
    }
    private static GlReadback.Pixels read(GlImageFrame frame) { try(var job=GlReadback.begin(frame)) { return read(job); } }
    private static GlReadback.Pixels read(GlMapFrame frame) { try(var job=GlReadback.begin(frame,GlReadback.Limits.DEFAULT)) { return read(job); } }
    private static GlReadback.Pixels read(GlNoiseFrame frame) { try(var job=GlReadback.begin(frame,GlReadback.Limits.DEFAULT)) { return read(job); } }
    private static void pixels(RgbaImage expected,RgbaImage actual) { equal(expected.width(),actual.width(),"pixel width");equal(expected.height(),actual.height(),"pixel height");for(int y=0;y<expected.height();y++)for(int x=0;x<expected.width();x++)equal(expected.rgba(x,y),actual.rgba(x,y),"exact top-left RGBA at "+x+","+y); }
    private static void deviceBoundaries(GlDevice device) {
        var info=device.driver();check(!info.vendor().isBlank()&&!info.renderer().isBlank()&&!info.shadingLanguage().isBlank(),"driver strings present");equal(device.maximumTextureSize(),info.maximumTexture(),"hardware texture receipt");equal(device.maximumRenderbufferSize(),info.maximumRenderbuffer(),"hardware renderbuffer receipt");
        var caught=new AtomicReference<Throwable>();var receipt=new AtomicReference<GlDevice.Driver>();var thread=new Thread(()-> { receipt.set(device.driver());try { device.stats(); }catch(Throwable failure) { caught.set(failure); } },"gpu-contract-foreign-thread");thread.setDaemon(true);thread.start();try { thread.join(5000); }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }check(!thread.isAlive(),"foreign-thread check completes");equal(info,receipt.get(),"detached driver receipt readable off-thread");check(caught.get() instanceof GlFailure gpu&&gpu.code()==GlFailure.Code.CONTEXT,"GPU calls reject foreign thread");
        var caps=GL.getCapabilities();try { GL.setCapabilities(null);failure(GlFailure.Code.CONTEXT,device::stats); }finally { GL.setCapabilities(caps); }
        var before=device.stats();failure(GlFailure.Code.LIMIT,()->GlTarget.create(device,65,1));equal(before,device.stats(),"invalid target dimensions allocate nothing");
        throwsType(UnsupportedOperationException.class,()->info.optionalFeatures().add("mutable"),"driver features immutable");
    }
    private static void shaders(GlDevice device) { try(var map=GlMapRenderer.create(device);var noise=GlNoiseRenderer.create(device);var world=GlRenderer.create(device)) { device.check("all renderer shaders compiled and linked"); } }
    private static void imagePixels(GlDevice device) {
        var source=image();try(var renderer=GlMapRenderer.create(device);var image=upload(device,source);var frame=GlImageFrame.render(renderer,1,image,new MapView(2,2,0,0,1),2,2,LIVE)) {
            var output=read(frame);pixels(source.image().orElseThrow(),output.image());equal(frame.info(),output.sourceImage().orElseThrow(),"readback exact original-image receipt");equal(source.receipt().orElseThrow(),frame.info().source(),"original decode receipt preserved");equal(COLORS[1],frame.hit(1.5,0.5).orElseThrow().rgba(),"original unpremultiplied alpha inspection");
        }
    }
    private static void mapPixels(GlDevice device) {
        var source=raster();try(var renderer=GlMapRenderer.create(device);var map=upload(device,source);var frame=GlMapFrame.render(renderer,1,map,new MapView(2,2,0,0,1),2,2,LIVE)) {
            var output=read(frame);pixels(source.image().orElseThrow(),output.image());equal(frame.info(),output.map().orElseThrow(),"readback exact map receipt");check(frame.info().scalarSource().orElseThrow().equals(ScalarHistogram.receipt(source.samples())),"frame keeps exact original scalar identity");var missing=frame.hit(1.5,1.5).orElseThrow();check(!missing.present()&&missing.noise().isEmpty(),"missing sample inspection preserves absence");
        }
    }
    private static void navigation(GlDevice device) {
        var source=image();try(var renderer=GlMapRenderer.create(device);var image=upload(device,source);var first=GlImageFrame.render(renderer,1,image,new MapView(2,2,0,0,2),4,4,LIVE);var second=GlImageFrame.render(renderer,2,image,new MapView(2,2,1,0,2),4,4,LIVE)) {
            var old=read(first).image();var moved=read(second).image();for(int y=0;y<4;y++)for(int x=0;x<4;x++) { equal(COLORS[y/2*2+x/2],old.rgba(x,y),"nearest exact 2x display magnification");equal(x==0?0:COLORS[y/2*2+(x-1)/2],moved.rgba(x,y),"panned image and clear border"); }
            equal(old.rgba(0,0),read(first).image().rgba(0,0),"older independently retained frame remains immutable");check(second.hit(0.5,0.5).isEmpty(),"navigation margin does not inspect a pixel");
        }
    }
    private static void uploadRecovery(GlDevice device) {
        var source=image();try(var job=GlImageUpload.begin(device,source)) { job.step(TINY,LIVE);throwsType(CancellationException.class,()->job.step(TINY,()->true),"obsolete upload cancels");equal(GlImageUpload.Phase.CANCELLED,job.progress().phase(),"cancelled upload terminal phase");failure(GlFailure.Code.STATE,job::take); }
        try(var queue=new GlImageQueue(device)) {
            check(queue.submit(1,source),"first queued image accepted");queue.advance(TINY,1);var before=queue.status();var invalid=SourceImage.decode(SourceImage.Encoded.capture(new SourceImage.Identity("fixtures/invalid.png",2),new byte[]{1}),LIVE);failure(GlFailure.Code.STATE,()->queue.submit(2,invalid));equal(before,queue.status(),"replacement preflight preserves earlier upload progress");check(!queue.submit(1,source),"same active token cannot overwrite its source");
            until(()->queue.status().phase()==GlImageQueue.Phase.READY,()->queue.advance(TINY,1));check(queue.take(2).isEmpty(),"newer explicit take invalidates previous token");check(queue.submit(2,source),"invalidated current token accepts complete retry source");until(()->queue.status().phase()==GlImageQueue.Phase.READY,()->queue.advance(TINY,2));try(var image=queue.take(2).orElseThrow().image()) { equal(source.receipt(),image.raster().receipt(),"recovered upload preserves exact original decode"); }
        }
    }
    private static RenderMemory.Hold denyAdmission(GlDevice device,long bytes) {
        var memory=device.memory().orElseThrow();var stats=memory.stats();long free=stats.limitBytes()-stats.chargedBytes();check(bytes>0&&free>=bytes,"candidate demand is positive and normally fits shared admission");
        return memory.hold("GPU candidate admission blocker",free-bytes+1);
    }
    private static void noAdmissionGrowth(GlDevice.Stats before,GlDevice.Stats after) {
        // reserve may collect unrelated signalled retirement fences even when new admission rejects.
        check(after.gpuBytes()<=before.gpuBytes()&&after.cpuPayloadBytes()<=before.cpuPayloadBytes()&&after.allocations()<=before.allocations()&&after.names()<=before.names(),"rejected replacement admits no extra device payload or names");equal(before.quarantined(),after.quarantined(),"rejected candidate introduces no quarantine");
    }
    private static void transferAdmission(GlDevice device) {
        for(boolean ready:new boolean[]{false,true}) {
            var source=image();try(var queue=new GlImageQueue(device)) {
                check(queue.submit(1,source),"initial queued image admitted");if(ready)until(()->queue.status().phase()==GlImageQueue.Phase.READY,()->queue.advance(TINY,1));var old=queue.status();
                try(var blocker=denyAdmission(device,source.payloadBytes())) { var counters=device.stats();var state=host();failure(GlFailure.Code.LIMIT,()->queue.submit(2,source));equal(old,queue.status(),"image admission rejection preserves accepted token/progress");noAdmissionGrowth(counters,device.stats());equal(state,host(),"image rejection preserves host bindings"); }
                until(()->queue.status().phase()==GlImageQueue.Phase.READY,()->queue.advance(TINY,1));try(var kept=queue.take(1).orElseThrow()) { equal(source.receipt(),kept.image().raster().receipt(),"original accepted image remains deliverable"); }
                check(queue.submit(2,source)&&queue.submit(3,source),"later image replacement can retire an admitted pending candidate");check(queue.take(2).isEmpty(),"successfully superseded image is withheld");until(()->queue.status().phase()==GlImageQueue.Phase.READY,()->queue.advance(TINY,3));queue.take(3).orElseThrow().close();
            }
            var map=raster();try(var queue=new GlMapQueue(device)) {
                check(queue.submit(1,map),"initial queued map admitted");if(ready)until(()->queue.status().phase()==GlMapQueue.Phase.READY,()->queue.advance(TINY,1));var old=queue.status();
                try(var blocker=denyAdmission(device,map.payloadBytes())) { var counters=device.stats();failure(GlFailure.Code.LIMIT,()->queue.submit(2,map));equal(old,queue.status(),"map admission rejection preserves accepted token/progress");noAdmissionGrowth(counters,device.stats()); }
                until(()->queue.status().phase()==GlMapQueue.Phase.READY,()->queue.advance(TINY,1));try(var kept=queue.take(1).orElseThrow()) { check(kept.map().raster()==map,"original exact map remains deliverable"); }
                check(queue.submit(2,map)&&queue.submit(3,map),"later map replacement succeeds");until(()->queue.status().phase()==GlMapQueue.Phase.READY,()->queue.advance(TINY,3));queue.take(3).orElseThrow().close();
            }
            try(var fixture=WorldGpuFixture.solid(0xffffffff,false);var renderer=GlRenderer.create(device);var queue=new GlUploadQueue(renderer)) {
                check(queue.submit(1,fixture.retain()),"initial world upload admitted");if(ready)until(()->queue.status().phase()==GlUploadQueue.Phase.READY,()->queue.advance(TINY,1));var old=queue.status();var fresh=fixture.retain();
                try(var blocker=denyAdmission(device,fixture.scene().usage().knownPayloadBytes())) { var counters=device.stats();failure(GlFailure.Code.LIMIT,()->queue.submit(2,fresh));check(fresh.closed(),"admission rejection consumes only fresh world lease");equal(old,queue.status(),"world queue rejection preserves accepted token/progress");noAdmissionGrowth(counters,device.stats()); }
                until(()->queue.status().phase()==GlUploadQueue.Phase.READY,()->queue.advance(TINY,1));try(var kept=queue.take(1).orElseThrow()) { check(kept.scene().cpuScene()==fixture.scene(),"original complete world remains deliverable"); }
                check(queue.submit(2,fixture.retain())&&queue.submit(3,fixture.retain()),"later admitted world replacement succeeds");until(()->queue.status().phase()==GlUploadQueue.Phase.READY,()->queue.advance(TINY,3));queue.take(3).orElseThrow().close();
            }
            try(var fixture=WorldGpuFixture.solid(0xffffffff,false);var renderer=GlRenderer.create(device);var view=new GlWorldView(renderer)) {
                view.view(worldCamera(fixture),32,32);check(view.submit(1,fixture.retain()),"initial connected world admitted");if(ready)until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,1));var old=view.status();var upload=view.uploadStatus();var fresh=fixture.retain();
                try(var blocker=denyAdmission(device,fixture.scene().usage().knownPayloadBytes())) { var counters=device.stats();failure(GlFailure.Code.LIMIT,()->view.submit(2,fresh));check(fresh.closed(),"connected world rejection consumes fresh source");equal(old,view.status(),"connected world preserves world/view tokens and ready phase");equal(upload,view.uploadStatus(),"connected world preserves underlying upload work");noAdmissionGrowth(counters,device.stats()); }
                until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,1));try(var kept=view.take(1,old.viewRevision()).orElseThrow()) { equal(fixture.scene().snapshot().source(),kept.info().source(),"old connected frame remains current after rejected replacement"); }
                check(view.submit(2,fixture.retain()),"connected world replacement reuses unspent revision");until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,2));view.take(2,view.status().viewRevision()).orElseThrow().close();
            }
        }
    }
    private static void outputAdmission(GlDevice device) {
        for(boolean ready:new boolean[]{false,true}) {
            try(var renderer=GlMapRenderer.create(device);var image=upload(device,image());var frame=GlImageFrame.render(renderer,1,image,new MapView(2,2,0,0,1),2,2,LIVE);var queue=new GlReadbackQueue(device)) {
                check(queue.submit(1,frame),"initial readback admitted");if(ready)until(()->queue.status().phase()==GlReadbackQueue.Phase.READY,()->queue.advance(TINY,1));var old=queue.status();
                try(var blocker=denyAdmission(device,16)) { var counters=device.stats();var state=host();failure(GlFailure.Code.LIMIT,()->queue.submit(2,frame));equal(old,queue.status(),"readback admission rejection preserves pending/ready output");noAdmissionGrowth(counters,device.stats());equal(state,host(),"readback admission failure preserves host state"); }
                until(()->queue.status().phase()==GlReadbackQueue.Phase.READY,()->queue.advance(TINY,1));try(var kept=queue.takeInput(1).orElseThrow()) { equal(frame.info(),kept.pixels().sourceImage().orElseThrow(),"old readback receipt remains deliverable");pixels(image.raster().image().orElseThrow(),kept.pixels().image()); }
                check(queue.submit(2,frame)&&queue.submit(3,frame),"later readback replacement succeeds");until(()->queue.status().phase()==GlReadbackQueue.Phase.READY,()->queue.advance(TINY,3));queue.takeInput(3).orElseThrow().close();
            }
            try(var fixture=WorldGpuFixture.animated(false);var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,null);var base=GlAtlasFrame.capture(scene);var queue=new GlAtlasQueue(scene,1)) {
                var plan=AtlasFramePlan.prepare(base.raster(),AtlasImages.updates(base.raster(),2,0,LIVE),LIVE);check(queue.submit(1,1,base,plan),"initial animated atlas update admitted");if(ready)until(()->queue.status().phase()==GlAtlasQueue.Phase.READY,()->queue.advance(TINY,1,1));var old=queue.status();
                try(var blocker=denyAdmission(device,plan.extraCpuBytes())) { var counters=device.stats();var state=host();var supplied=RenderInput.own(plan,RenderMemory.Hold.none());failure(GlFailure.Code.LIMIT,()->queue.submit(1,2,base,supplied));check(supplied.closed(),"rejected atlas replacement consumes fresh CPU-plan wrapper");equal(old,queue.status(),"atlas admission rejection preserves old scene/frame tokens and copy progress");noAdmissionGrowth(counters,device.stats());equal(state,host(),"atlas admission rejection preserves host state"); }
                until(()->queue.status().phase()==GlAtlasQueue.Phase.READY,()->queue.advance(TINY,1,1));try(var kept=queue.take(1,1,base).orElseThrow()) { equal(2L,kept.frame().raster().tick(),"original prepared animated frame remains deliverable"); }
                check(queue.submit(1,2,base,plan)&&queue.submit(1,3,base,plan),"later atlas replacement can retire admitted pending plan");until(()->queue.status().phase()==GlAtlasQueue.Phase.READY,()->queue.advance(TINY,1,3));queue.take(1,3,base).orElseThrow().close();
            }
        }
    }
    private static void readbackRecovery(GlDevice device) {
        try(var renderer=GlMapRenderer.create(device);var image=upload(device,image());var frame=GlImageFrame.render(renderer,1,image,new MapView(2,2,0,0,1),2,2,LIVE);var queue=new GlReadbackQueue(device)) {
            check(queue.submit(1,frame),"first queued readback accepted");until(()->queue.status().phase()==GlReadbackQueue.Phase.READY,()->queue.advance(TINY,1));var before=queue.status();var closed=frame.retain();closed.close();failure(GlFailure.Code.STATE,()->queue.submit(2,closed));equal(before,queue.status(),"invalid replacement preserves READY pixels and token");
            try(var owned=queue.takeInput(1).orElseThrow()) { equal(frame.info(),owned.pixels().sourceImage().orElseThrow(),"owned readback exact receipt");pixels(image.raster().image().orElseThrow(),owned.pixels().image()); }check(queue.take(1).isEmpty(),"readback completion delivered once");
        }
    }
    private static List<Integer> host() {
        var values=new ArrayList<Integer>();for(int name:new int[]{GL_CURRENT_PROGRAM,GL_VERTEX_ARRAY_BINDING,GL_ACTIVE_TEXTURE,GL_TEXTURE_BINDING_2D,GL_PIXEL_PACK_BUFFER_BINDING,GL_PIXEL_UNPACK_BUFFER_BINDING,GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_PIXELS,GL_PACK_SKIP_ROWS,GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_SKIP_PIXELS,GL_UNPACK_SKIP_ROWS,GL_DRAW_FRAMEBUFFER_BINDING,GL_READ_FRAMEBUFFER_BINDING,GL_DEPTH_FUNC,GL_CULL_FACE_MODE,GL_FRONT_FACE})values.add(glGetInteger(name));
        for(int name:new int[]{GL_BLEND,GL_SCISSOR_TEST,GL_DEPTH_TEST,GL_CULL_FACE,GL_DITHER,GL_FRAMEBUFFER_SRGB,GL_RASTERIZER_DISCARD,GL_CLIP_DISTANCE0})values.add(glIsEnabled(name)?1:0);
        for(int name:new int[]{GL_VIEWPORT,GL_SCISSOR_BOX,GL_COLOR_WRITEMASK}) { int[] vector=new int[4];glGetIntegerv(name,vector);for(int value:vector)values.add(value); }values.add(glGetBoolean(GL_DEPTH_WRITEMASK)?1:0);return List.copyOf(values);
    }
    private static void hostState(GlDevice device) {
        try(var renderer=GlMapRenderer.create(device);var draw=new GlState(device);var transfer=new GlTransferState(device);var pack=new GlPackState(device)) {
            glViewport(3,5,7,9);glScissor(1,2,3,4);glEnable(GL_SCISSOR_TEST);glEnable(GL_BLEND);glEnable(GL_DEPTH_TEST);glEnable(GL_CULL_FACE);glEnable(GL_DITHER);glEnable(GL_FRAMEBUFFER_SRGB);glEnable(GL_RASTERIZER_DISCARD);glEnable(GL_CLIP_DISTANCE0);glDepthFunc(GL_GREATER);glDepthMask(false);glFrontFace(GL_CW);glColorMask(false,true,false,true);
            glPixelStorei(GL_PACK_ALIGNMENT,8);glPixelStorei(GL_PACK_ROW_LENGTH,7);glPixelStorei(GL_PACK_SKIP_PIXELS,1);glPixelStorei(GL_PACK_SKIP_ROWS,2);glPixelStorei(GL_UNPACK_ALIGNMENT,8);glPixelStorei(GL_UNPACK_ROW_LENGTH,9);glPixelStorei(GL_UNPACK_SKIP_PIXELS,2);glPixelStorei(GL_UNPACK_SKIP_ROWS,1);glActiveTexture(GL_TEXTURE3);var before=host();
            var source=image();try(var image=upload(device,source);var frame=GlImageFrame.render(renderer,1,image,new MapView(2,2,0,0,1),2,2,LIVE)) { equal(before,host(),"upload and drawing restore hostile host state");pixels(source.image().orElseThrow(),read(frame).image());equal(before,host(),"readback restores pack and drawing state"); }
        }
    }
    private static void scalar(GlDevice device) {
        var context=context();var grid=new NoiseVolume.Grid(new MapSamples.Grid(0,0,1,1,1),new MapSamples.Vertical(0,1));var volume=NoiseVolume.capture(context,grid,new double[]{0.5},new byte[]{1},NoiseVolume.Limits.DEFAULT,LIVE);var style=new NoiseSurface.Style(0,1,new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE));var source=NoiseSurface.prepare(volume,style,NoiseSurface.Limits.DEFAULT,LIVE);
        var camera=new GlCamera(new WorldSnapshot.Position(0,0,0),0.5,0.5,3,0.5,0.5,0.5,60,0.1,20);
        try(var renderer=GlNoiseRenderer.create(device);var scene=upload(device,source);var frame=GlNoiseFrame.render(renderer,1,scene,camera,16,16,LIVE);var clipped=GlNoiseFrame.render(renderer,2,scene,camera,16,16,new WorldClip(new WorldSnapshot.Extent(10,0,10,1,1,1)),LIVE)) {
            check(frame.info().stats().submittedFaces()>0,"scalar drawing submits complete visible batches");equal(0,clipped.info().stats().submittedFaces(),"disjoint display clip rejects all scalar batches");var shown=read(frame).image();var hidden=read(clipped).image();check(shown.rgba(8,8)!=hidden.rgba(8,8),"scalar interior pixel differs from clipped clear target");equal(frame.info(),read(frame).noise().orElseThrow(),"readback exact scalar receipt");
            var histogram=ScalarHistogram.capture(volume,Optional.of(new ScalarHistogram.Range(0,1)),ScalarHistogram.Limits.DEFAULT,LIVE);check(frame.info().matches(histogram)&&clipped.info().matches(histogram),"full-source histogram identity survives camera/clip changes");
        }
    }
    private static void connectedScalarRecolor(GlDevice device) {
        var volume=NoiseVolume.capture(context(),new NoiseVolume.Grid(new MapSamples.Grid(0,0,1,1,1),new MapSamples.Vertical(0,1)),new double[]{0.5},new byte[]{1},NoiseVolume.Limits.DEFAULT,LIVE);var colors=new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE);var style=new NoiseSurface.Style(0,1,colors);var limits=new NoiseSurface.Limits(64,1024*1024);var histLimits=new ScalarHistogram.Limits(4,1,2,65536,1024*1024);var camera=new GlCamera(new WorldSnapshot.Position(0,0,0),0.5,0.5,3,0.5,0.5,0.5,60,0.1,20);
        try(var renderer=GlNoiseRenderer.create(device);var view=new GlNoiseView(renderer,4L*1024*1024)) {
            long first=view.submit(new NoiseSurfaceWorker.Request(volume,style,limits).withHistogram(histLimits));view.view(camera,16,16);until(()->view.status().phase()==GlNoiseView.Phase.FRAME_READY,()-> { var status=view.advance(TINY,first);check(status.phase()!=GlNoiseView.Phase.FAILED,"connected scalar preparation succeeds: "+status.reason()); });
            try(var original=view.take(first,view.status().viewRevision()).orElseThrow()) {
                var originalPixels=read(original).image();var surface=original.surface();var red=new MapRaster.ScalarStyle(0,1,new MapRamp(List.of(new MapRamp.Stop(0,0xff1010ff),new MapRamp.Stop(1,0xff1010ff))));var blue=new MapRaster.ScalarStyle(0,1,new MapRamp(List.of(new MapRamp.Stop(0,0x1010ffff),new MapRamp.Stop(1,0x1010ffff))));
                long obsolete=view.recolor(surface,red,limits),latest=view.recolor(surface,blue,limits);boolean[] copied={false};
                until(()->view.status().phase()==GlNoiseView.Phase.FRAME_READY,()-> { var status=view.advance(TINY,latest);check(status.phase()!=GlNoiseView.Phase.FAILED,"latest connected scalar recolor succeeds: "+status.reason());view.uploadProgress().ifPresent(progress->{if(progress.totalCopyBytes()==8L*surface.size())copied[0]=true;}); });
                check(copied[0],"connected rapid recoloring uses retained original GPU geometry");var ready=view.status();equal(ready,view.advance(TINY,obsolete),"obsolete connected revision cannot advance latest recolor");check(view.take(obsolete,ready.viewRevision()).isEmpty(),"obsolete connected recolor cannot be adopted");
                try(var frame=view.take(latest,ready.viewRevision()).orElseThrow();var histogram=view.retainHistogram(latest).orElseThrow()) {
                    equal(blue,frame.info().style().colors(),"latest rapid recolor reaches completed frame");check(frame.info().matches(histogram.value()),"retained histogram still matches latest recolored source/range");pixels(originalPixels,read(original).image());check(originalPixels.rgba(8,8)!=read(frame).image().rgba(8,8),"retained old image stays independent of connected recolor");view.close();until(()->view.reservedCpuBytes()==0,()->{});equal(volume.id(),frame.info().volumeId(),"completed frame survives connected view closure");check(frame.info().matches(histogram.value()),"caller-owned histogram survives connected view closure");
                }
            }
        }
        glFinish();device.collect();
    }
    private static void scalarRecolor(GlDevice device) {
        for(var meshing:NoiseSurface.Meshing.values()) {
            int span=meshing==NoiseSurface.Meshing.CELLS?32:1;var values=new double[span*span];Arrays.fill(values,0.5);var present=new byte[values.length];Arrays.fill(present,(byte)1);
            var volume=NoiseVolume.capture(context(),new NoiseVolume.Grid(new MapSamples.Grid(0,0,span,span,1),new MapSamples.Vertical(0,1)),values,present,NoiseVolume.Limits.DEFAULT,LIVE);
            var source=NoiseSurface.prepare(volume,new NoiseSurface.Style(0,1,new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE),meshing),NoiseSurface.Limits.DEFAULT,LIVE);
            var colors=new MapRaster.ScalarStyle(0,1,new MapRamp(List.of(new MapRamp.Stop(0,0xd04010ff),new MapRamp.Stop(1,0xd04010ff))));var changed=NoiseSurface.recolor(source,colors,NoiseSurface.Limits.DEFAULT,LIVE);check(changed.sharesGeometry(source),"recolor proves original layout without scanning");
            var camera=new GlCamera(new WorldSnapshot.Position(0,0,0),span/2.0,span==1?0.5:12,span==1?3:55,span/2.0,0.5,span==1?0.5:27,60,0.1,128);var clip=span==1?WorldClip.NONE:new WorldClip(new WorldSnapshot.Extent(0,0,24,32,1,8));
            int copyRead=glGenBuffers(),copyWrite=glGenBuffers();
            try(var bindings=new GlTransferState(device)) {
                glBindBuffer(GL_COPY_READ_BUFFER,copyRead);glBufferData(GL_COPY_READ_BUFFER,64,GL_STATIC_DRAW);glBindBuffer(GL_COPY_WRITE_BUFFER,copyWrite);glBufferData(GL_COPY_WRITE_BUFFER,64,GL_STATIC_DRAW);
                try(var renderer=GlNoiseRenderer.create(device);var original=upload(device,source);var oldFrame=GlNoiseFrame.render(renderer,1,original,camera,32,32,clip,LIVE)) {
                    var oldPixels=read(oldFrame).image();long[] cpu={0},copied={0};GlNoiseScene recolored;
                    try(var job=GlNoiseUpload.recolor(original,changed)) {
                        original.close();until(job::ready,()-> { var step=job.step(TINY,LIVE);budget(step.transferBytes()+step.copiedBytes(),step.actions(),step.workUnits());cpu[0]+=step.transferBytes();copied[0]+=step.copiedBytes();equal(copyRead,glGetInteger(GL_COPY_READ_BUFFER),"copy read binding restored after every step");equal(copyWrite,glGetInteger(GL_COPY_WRITE_BUFFER),"copy write binding restored after every step"); });
                        equal(4L*source.size(),cpu[0],"recolor uploads only four CPU color bytes per face");equal(8L*source.size(),copied[0],"recolor copies complete original geometry on GPU");equal(cpu[0],job.progress().uploadedBytes(),"CPU progress excludes GPU copy traffic");equal(copied[0],job.progress().copiedBytes(),"GPU copy progress is separate");recolored=job.take();
                    }
                    try(recolored;var reference=upload(device,changed);var actual=GlNoiseFrame.render(renderer,2,recolored,camera,32,32,clip,LIVE);var expected=GlNoiseFrame.render(renderer,3,reference,camera,32,32,clip,LIVE)) {
                        pixels(read(expected).image(),read(actual).image());pixels(oldPixels,read(oldFrame).image());check(oldPixels.rgba(16,16)!=read(actual).image().rgba(16,16),"new color image changes without mutating retained old frame");if(span>1)check(actual.info().stats().culledBatches()>0,"split geometry/color offsets survive skipped leading batches");
                        var independent=NoiseSurface.prepare(volume,changed.style(),NoiseSurface.Limits.DEFAULT,LIVE);var before=device.stats();failure(GlFailure.Code.STALE,()->GlNoiseUpload.recolor(recolored,independent));equal(before,device.stats(),"nonshared geometry rejects before admission");
                        try(var cancelled=GlNoiseUpload.recolor(recolored,changed)) { cancelled.step(TINY,LIVE);throwsType(CancellationException.class,()->cancelled.step(TINY,()->true),"scalar copy cancellation");equal(GlNoiseUpload.Phase.CANCELLED,cancelled.progress().phase(),"cancelled recolor publishes no scene");failure(GlFailure.Code.STATE,cancelled::take); }
                        try(var other=new RenderMemory(new RenderMemory.Limits(changed.payloadBytes(),1))) { var owner=RenderInput.own(changed,other.hold("Foreign scalar colors",changed.payloadBytes()));throwsType(IllegalArgumentException.class,()->GlNoiseUpload.recolor(recolored,owner),"foreign recolor owner rejected");check(owner.closed(),"rejected recolor consumes fresh CPU owner");equal(0L,other.stats().chargedBytes(),"foreign recolor owner admission released"); }
                        var input=RenderInput.own(changed,RenderMemory.Hold.none());try(var owned=GlNoiseUpload.recolor(recolored,input)) { check(input.closed(),"owned recolor consumes CPU wrapper through admission");until(owned::ready,()->owned.step(TINY,LIVE));try(var scene=owned.take()) { check(scene.surface()==changed,"owned recolor retains exact CPU surface"); } }
                    }
                }
            }finally { glDeleteBuffers(copyRead);glDeleteBuffers(copyWrite); }
            glFinish();device.collect();
        }
        var emptyVolume=NoiseVolume.capture(context(),new NoiseVolume.Grid(new MapSamples.Grid(0,0,1,1,1),new MapSamples.Vertical(0,1)),new double[]{0},new byte[]{0},NoiseVolume.Limits.DEFAULT,LIVE);var style=new NoiseSurface.Style(0,1,new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE));var empty=NoiseSurface.prepare(emptyVolume,style,NoiseSurface.Limits.DEFAULT,LIVE);var recolored=NoiseSurface.recolor(empty,style.colors(),NoiseSurface.Limits.DEFAULT,LIVE);
        try(var scene=upload(device,empty);var job=GlNoiseUpload.recolor(scene,recolored)) { until(job::ready,()->job.step(TINY,LIVE));equal(0L,job.progress().uploadedBytes(),"empty recolor uploads no bytes");equal(0L,job.progress().copiedBytes(),"empty recolor copies no bytes");try(var result=job.take()) { equal(0,result.surface().size(),"empty recolor invents no geometry"); } }
        connectedScalarRecolor(device);
    }
    private static GlCamera worldCamera(WorldGpuFixture fixture) {
        var extent=fixture.scene().snapshot().extent();return new GlCamera(extent.position(0),0.5,0.5,extent.depth()+2,0.5,0.5,0.5,60,0.1,20);
    }
    private static GlScene upload(GlRenderer renderer,WorldGpuFixture fixture,WorldTint tint,WorldLighting lighting) {
        var owner=fixture.retain();var input=lighting==null?null:RenderInput.own(lighting,RenderMemory.Hold.none());try(var job=input==null?GlUpload.begin(renderer,owner,tint):GlUpload.beginLit(renderer,owner,tint,input)) {
            if(input!=null)check(input.closed(),"owned final lighting wrapper consumed through GPU admission");
            failure(GlFailure.Code.STATE,job::take);var before=renderer.device.stats();failure(GlFailure.Code.STATE,()->GlUpload.begin(renderer,owner,tint,lighting));check(!owner.closed(),"same GPU-owned CPU lease is protected");equal(before,renderer.device.stats(),"duplicate scene transfer allocates nothing");
            until(job::ready,()-> { var step=job.step(TINY,LIVE);budget(step.transferBytes(),step.actions(),step.workUnits()); });equal(fixture.scene().sections().size(),job.progress().completedSections(),"all world sections uploaded");equal(fixture.scene().usage().faces(),job.progress().validatedFaces(),"all original world faces validated");equal(job.progress().totalBytes(),job.progress().uploadedBytes(),"complete world geometry/atlas/marker transfer");return job.take();
        }
    }
    private static GlFrame frame(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,WorldClip clip) { return GlFrame.render(renderer,revision,scene,atlas,order,camera,32,32,clip,LIVE); }
    private static GlReadback.Pixels read(GlFrame frame) { try(var job=GlReadback.begin(frame,GlReadback.Limits.DEFAULT)) { return read(job); } }
    private static GlAtlasFrame update(GlAtlasFrame current,long tick) {
        var change=AtlasImages.updates(current.raster(),tick,0,LIVE);check(!change.requiresPlaceholder(),"complete synthetic animation update");var plan=AtlasFramePlan.prepare(current.raster(),change,LIVE);check(!plan.requiresPlaceholder(),"complete synthetic animation plan");
        try(var job=GlAtlasUpload.begin(current,plan)) { until(job::ready,()-> { var step=job.step(TINY,LIVE);budget(step.copiedBytes()+step.uploadedBytes(),step.actions(),step.workUnits()); });equal(job.progress().totalCopyBytes(),job.progress().copiedBytes(),"complete atlas page copy");equal(job.progress().totalUploadBytes(),job.progress().uploadedBytes(),"complete atlas patch upload");return job.take(current); }
    }
    private static void worldGeometry(GlDevice device) {
        try(var fixture=WorldGpuFixture.neighbors();var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,fixture.lighting(1,1,1))) {
            equal(10,fixture.scene().usage().faces(),"two explicit hidden neighbor faces omitted");equal(1,fixture.scene().snapshot().extent().height(),"complete supplied height preserved");var camera=worldCamera(fixture);var at=fixture.scene().snapshot().extent().position(0);var clip=new WorldClip(new WorldSnapshot.Extent(Math.addExact(at.x(),8),at.y(),at.z(),1,1,1));
            try(var original=frame(renderer,1,scene,null,null,camera,WorldClip.NONE);var hidden=frame(renderer,2,scene,null,null,camera,clip);var index=WorldPickIndex.prepare(original.retainCpuScene(),LIVE)) {
                var pixels=read(original);equal(0xffffffff,pixels.image().rgba(16,16),"opaque texture and supplied unit light render exact white");equal(original.info(),pixels.world().orElseThrow(),"world readback preserves exact completed frame");equal(60L,original.info().stats().triangleIndices(),"all ten source quads submitted once");equal(0L,hidden.info().stats().triangleIndices(),"disjoint clip culls world geometry");check(pixels.image().rgba(16,16)!=read(hidden).image().rgba(16,16),"clipped image has clear center");check(original.info().visuals().orElseThrow().equals(WorldVisualReport.receipt(fixture.scene())),"world frame carries exact original assignments");
                var ray=original.info().ray(16.5,16.5);var raster=original.raster();var hit=index.query(ray,original.info().pickOptions(WorldPickIndex.Options.DEFAULT),raster,LIVE).hit().orElseThrow();equal(at,hit.position(),"picked original coordinate beyond exact double integer range");equal(fixture.scene().snapshot().states().getFirst(),hit.state(),"picked original native ID/properties");equal(fixture.scene().snapshot().biomes().getFirst(),hit.biome(),"picked original numeric biome/key");check(index.query(ray,hidden.info().pickOptions(WorldPickIndex.Options.DEFAULT),hidden.raster(),LIVE).hit().isEmpty(),"displayed clip also scopes original-state picking");
                original.close();scene.close();equal(hit.state(),index.query(ray,WorldPickIndex.Options.DEFAULT,raster,LIVE).hit().orElseThrow().state(),"independent CPU index survives GPU scene/frame retirement");
            }
        }
    }
    private static void worldAlpha(GlDevice device) {
        try(var fixture=WorldGpuFixture.cutout();var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,fixture.lighting(1,1,1));var output=frame(renderer,1,scene,null,null,worldCamera(fixture),WorldClip.NONE);var index=WorldPickIndex.prepare(output.retainCpuScene(),LIVE)) {
            var image=read(output).image();int clear=image.rgba(0,0);equal(Set.of(clear,0x00ff00ff),new HashSet<>(List.of(image.rgba(12,16),image.rgba(20,16))),"cutout shader shows exactly one opaque and one transparent half");equal(0,output.info().stats().translucentFaces(),"cutout faces never enter translucent pass");
            for(int x:new int[]{12,20}) { var ray=output.info().ray(x+0.5,16.5);var result=index.query(ray,WorldPickIndex.Options.DEFAULT,output.raster(),LIVE);equal(image.rgba(x,16)==0x00ff00ff,result.hit().isPresent(),"displayed-alpha picking agrees with cutout pixel");var geometry=new WorldPickIndex.Options(131071,262144,65536,false,WorldPickIndex.Alpha.GEOMETRY);check(index.query(ray,geometry,output.raster(),LIVE).hit().isPresent(),"geometry-only query still sees original transparent face"); }
        }
        try(var fixture=WorldGpuFixture.translucent();var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,fixture.lighting(1,1,1))) {
            var camera=worldCamera(fixture);var before=device.stats();failure(GlFailure.Code.MATERIAL,()->frame(renderer,1,scene,null,null,camera,WorldClip.NONE));equal(before,device.stats(),"missing translucent order rejects before target admission");var cpuOrder=WorldDrawOrder.prepare(fixture.scene(),camera.drawView(),LIVE);check(!cpuOrder.requiresPlaceholder(),"complete synthetic global face order");
            try(var order=GlDrawPlan.create(scene,cpuOrder);var output=frame(renderer,1,scene,null,order,camera,WorldClip.NONE)) {
                var image=read(output).image();int clear=image.rgba(0,0),actual=image.rgba(16,16);double alpha=128.0/255;
                for(int shift:new int[]{24,16,8}) { int background=clear>>>shift&255,far=shift==8?255:0,near=shift==24?255:0;int first=(int)Math.round(far*alpha+background*(1-alpha));int expected=(int)Math.round(near*alpha+first*(1-alpha));check(Math.abs(expected-(actual>>>shift&255))<=2,"back-to-front red-over-blue analytical blending at channel "+shift); }equal(255,actual&255,"translucent pass preserves opaque target alpha");equal(12,output.info().stats().translucentFaces(),"complete two-cube order submitted");
                var moved=new GlCamera(camera.anchor(),0.6,0.5,camera.eyeZ(),0.5,0.5,0.5,60,0.1,20);before=device.stats();failure(GlFailure.Code.STALE,()->frame(renderer,2,scene,null,order,moved,WorldClip.NONE));equal(before,device.stats(),"wrong camera order preserves earlier completed image");
            }
        }
    }
    private static void worldAnimation(GlDevice device) {
        for(boolean interpolate:new boolean[]{false,true})try(var fixture=WorldGpuFixture.animated(interpolate);var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,fixture.lighting(1,1,1));var base=GlAtlasFrame.capture(scene);var original=frame(renderer,1,scene,base,null,worldCamera(fixture),WorldClip.NONE);var midpoint=update(base,1);var middle=frame(renderer,2,scene,midpoint,null,worldCamera(fixture),WorldClip.NONE);var finalAtlas=update(midpoint,2);var end=frame(renderer,3,scene,finalAtlas,null,worldCamera(fixture),WorldClip.NONE)) {
            equal(0xff0000ff,read(original).image().rgba(16,16),"original animated atlas is red");equal(interpolate?0x800080ff:0xff0000ff,read(middle).image().rgba(16,16),"known midpoint interpolation or unchanged scheduled tile");equal(0x0000ffff,read(end).image().rgba(16,16),"scheduled next tile is blue");equal(0L,original.info().atlasTick(),"old frame keeps its original animation clock");equal(1L,middle.info().atlasTick(),"midpoint clock receipt");equal(2L,end.info().atlasTick(),"next-tile clock receipt");var wrongPlan=AtlasFramePlan.prepare(base.raster(),AtlasImages.updates(base.raster(),2,0,LIVE),LIVE);var prior=device.stats();failure(GlFailure.Code.STALE,()->GlAtlasUpload.begin(finalAtlas,wrongPlan));equal(prior,device.stats(),"open atlas with wrong baseline rejects before admission");midpoint.close();finalAtlas.close();equal(0xff0000ff,read(original).image().rgba(16,16),"old completed world pixels remain immutable after atlas replacement");equal(0x0000ffff,read(end).image().rgba(16,16),"completed pixels outlive atlas owner closure");
            var stale=AtlasFramePlan.prepare(base.raster(),AtlasImages.updates(base.raster(),2,0,LIVE),LIVE);var before=device.stats();failure(GlFailure.Code.STATE,()->GlAtlasUpload.begin(midpoint,stale));equal(before,device.stats(),"closed atlas replacement rejects before admission");
        }
    }
    /** Read-only lifecycle inspection; no job, cache, raster or admission state is patched. */
    private static AtlasFrameWorker.Request clockObservation(GlWorldAnimation animation) {
        try { var field=GlWorldAnimation.class.getDeclaredField("observation");field.setAccessible(true);return (AtlasFrameWorker.Request)field.get(animation); }
        catch(ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect exact animation observation",failure); }
    }
    private static void atlasClockIndex(GlDevice device) {
        try(var fixture=WorldGpuFixture.solid(0xffffffff,false);var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,fixture.lighting(1,1,1));var worker=new AtlasFrameWorker(0,device.memory().orElse(null));var animation=new GlWorldAnimation(scene,1,worker)) {
            check(scene.raster().layout().timeVaryingSprites().isEmpty(),"static GPU world has no time-varying clock slots");animation.clock(1,0);check(!animation.advance(TINY),"static clock requires no atlas upload");var proof=clockObservation(animation);var stats=device.stats();
            for(long tick:new long[]{2,10,Long.MAX_VALUE,0,Long.MAX_VALUE-1}) { animation.clock(tick,0.75);check(!animation.advance(TINY),"static clock observation needs no replacement");check(clockObservation(animation)==proof,"static forward/backward clocks reuse exact observation without another palette walk");equal(0L,worker.reservedBytes(),"static zero-budget worker never reserves payload");equal(0L,animation.status().revision(),"static clock never increments update revision");equal(stats,device.stats(),"static clocks allocate no extra GPU journal or payload"); }
            animation.playbackClock(4,0);animation.advance(TINY);check(clockObservation(animation)==proof&&animation.presentable(),"switching static clock modes reuses proof and primes correctly");equal(0L,animation.status().displayedTick(),"static clock does not relabel displayed base receipt");animation.close();check(clockObservation(animation)==null,"close detaches observation from source graph");
        }
        for(boolean interpolate:new boolean[]{false,true})try(var fixture=WorldGpuFixture.animated(interpolate,10000);var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,fixture.lighting(1,1,1));var worker=new AtlasFrameWorker(1024L*1024*1024,device.memory().orElse(null));var animation=new GlWorldAnimation(scene,1,worker)) {
            animation.clock(0,0.5);animation.advance(TINY);var initial=clockObservation(animation);equal(interpolate?9L:9999L,initial.lastTick(),"known stepped or quantized interval endpoint");var before=device.stats();
            for(int tick=1;tick<6;tick++) { animation.clock(tick,0.9);check(!animation.advance(TINY),"clock inside initial interval needs no update");check(clockObservation(animation)==initial,"unchanged interval reuses exact metadata observation");equal(before,device.stats(),"unchanged dynamic interval adds no GPU payload"); }
            long boundary=initial.lastTick()+1;animation.clock(boundary,0);animation.advance(TINY);var changed=clockObservation(animation);check(changed!=initial&&changed.changedSprites()==1,"crossing conservative endpoint captures a fresh changed sample");animation.clock(boundary+1,0.75);animation.advance(TINY);check(clockObservation(animation)==changed,"same pending interval avoids another palette/sample comparison");
            boolean[] adopted={false};until(()->adopted[0],()->adopted[0]=animation.advance(TINY));check(clockObservation(animation)==null,"atlas adoption invalidates old exact-raster observation");equal(boundary,animation.status().displayedTick(),"coalesced pending interval retains actual preparation clock");animation.advance(TINY);var current=clockObservation(animation);check(current!=initial&&current!=changed&&current.expected()==animation.frame().raster(),"next observation belongs to newly displayed raster only");equal(0,current.changedSprites(),"current canonical interval settles without another upload");
            animation.clock(0,0);animation.advance(TINY);check(clockObservation(animation)!=current&&clockObservation(animation).tick()==0,"backward dynamic seek captures a new exact-baseline proof");animation.close();check(clockObservation(animation)==null,"pending seek shutdown drops cached proof");until(()->worker.reservedBytes()==0,Thread::yield);
        }
        try(var fixture=WorldGpuFixture.animated(false,10000);var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,null);var worker=new AtlasFrameWorker(0,device.memory().orElse(null));var animation=new GlWorldAnimation(scene,1,worker)) {
            animation.clock(10000,0);throwsType(IllegalStateException.class,()->animation.advance(TINY),"failed animated admission remains explicit");check(clockObservation(animation)==null,"failed admission detaches its metadata proof");animation.clock(0,0);animation.advance(TINY);check(clockObservation(animation)!=null&&clockObservation(animation).changedSprites()==0,"retry observes current pixels afresh within original zero allowance");
        }
    }
    private static void liveAtlasPlayback(GlDevice device) {
        for(boolean interpolate:new boolean[]{false,true})try(var fixture=WorldGpuFixture.animated(interpolate);var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,fixture.lighting(1,1,1));var worker=new AtlasFrameWorker(1024L*1024*1024,device.memory().orElse(null));var animation=new GlWorldAnimation(scene,1,worker)) {
            var initial=animation.status();var admitted=device.stats();
            throwsType(IllegalArgumentException.class,()->animation.playbackClock(-1,0),"negative live tick rejected");throwsType(IllegalArgumentException.class,()->animation.playbackClock(2,Double.NaN),"nonfinite live partial rejected");throwsType(IllegalArgumentException.class,()->animation.playbackClock(2,1),"live partial endpoint rejected");equal(initial,animation.status(),"invalid live clocks preserve status");equal(admitted,device.stats(),"invalid live clocks allocate nothing");
            animation.playbackClock(2,0.25);check(!animation.presentable(),"live playback does not prime with unrelated base pixels");
            long[] latest={2};boolean[] adopted={false};
            until(()->adopted[0],()-> {
                animation.playbackClock(latest[0]++,0.25);var state=host();adopted[0]=animation.advance(TINY);equal(state,host(),"live atlas stepping restores host state");
                equal(1L,animation.status().revision(),"continuously advancing live clocks retain one admitted sample");check(animation.status().phase()!=GlWorldAnimation.Phase.FAILED,"live sample progresses without failure");if(!adopted[0])check(!animation.presentable(),"initial live sample stays hidden until complete");
            });
            equal(2L,animation.status().displayedTick(),"first admitted live sample finishes despite changing clocks");equal(0.25,animation.status().displayedPartialTick(),"displayed partial receipt describes actual sample");equal(latest[0]-1,animation.status().requestedTick(),"latest desired live clock remains visible");check(animation.presentable()&&!animation.settled(),"complete live sample can present while its latest clock catches up");
            try(var saved=frame(renderer,1,scene,animation.frame(),null,worldCamera(fixture),WorldClip.NONE)) {
                equal(0x0000ffff,read(saved).image().rgba(16,16),"first completed live sample has blue GPU pixels");
                long paused=latest[0]+Math.floorMod(1-latest[0],4);animation.playbackClock(paused,0.75);
                until(animation::settled,()->animation.advance(TINY));equal(2L,animation.status().revision(),"pause prepares only the latest clock after first adoption");equal(paused,animation.status().displayedTick(),"paused playback catches up exactly");equal(0.75,animation.status().displayedPartialTick(),"pause retains actual fractional receipt");
                try(var middle=frame(renderer,2,scene,animation.frame(),null,worldCamera(fixture),WorldClip.NONE)) { equal(interpolate?0x800080ff:0xff0000ff,read(middle).image().rgba(16,16),"paused scheduled or interpolated sample matches CPU convention"); }
                animation.playbackClock(paused+1,0);until(()->animation.status().phase()==GlWorldAnimation.Phase.UPLOADING,()->animation.advance(TINY));
                long pending=animation.status().revision();animation.clock(paused,0.75);check(!animation.advance(TINY),"explicit seek back to current pixels discards partial live update");equal(pending+1,animation.status().revision(),"seek invalidates pending live sample");check(animation.settled()&&animation.presentable(),"seek to displayed pixels settles without replacement");equal(paused,animation.status().displayedTick(),"cancelled live sample never replaces current raster");
                animation.playbackClock(paused+1,0);animation.advance(TINY);animation.clock(paused+1,0);check(!animation.presentable(),"switch to seek mode at the same desired clock waits for exact sample");
                until(animation::settled,()->animation.advance(TINY));equal(paused+1,animation.status().displayedTick(),"same-clock seek completes its pending pixels");equal(0x0000ffff,read(saved).image().rgba(16,16),"saved live frame remains immutable after pause and seeks");
            }
            animation.playbackClock(1,0);animation.advance(TINY);animation.close();failure(GlFailure.Code.STATE,()->animation.playbackClock(2,0));until(()->worker.reservedBytes()==0,Thread::yield);check(!worker.status().phase().equals(AtlasFrameWorker.Phase.CLOSED),"animation shutdown preserves borrowed worker");
        }
    }
    private static void liveWorldPlayback(GlDevice device) {
        for(boolean interpolate:new boolean[]{false,true})try(var fixture=WorldGpuFixture.animated(interpolate);var renderer=GlRenderer.create(device);var view=new GlWorldView(renderer)) {
            var light=fixture.lighting(1,1,1);view.view(worldCamera(fixture),32,32);view.playbackClock(2,0.5);check(view.submit(1,fixture.retain(),null,light),"live world accepts clock before scene upload");long[] latest={2};
            until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()-> {
                if(view.animationStatus().isPresent()&&view.animationStatus().orElseThrow().revision()>0)view.playbackClock(++latest[0],0.5);
                var state=view.advance(TINY,1);check(state.phase()!=GlWorldView.Phase.FAILED,"continuous initial playback does not fail");
            });
            try(var first=view.take(1,view.status().viewRevision()).orElseThrow()) {
                equal(2L,first.info().atlasTick(),"continuous playback presents first admitted sample without starving initial image");equal(0x0000ffff,read(first).image().rgba(16,16),"initial live image is complete blue rather than base red");check(view.animationStatus().orElseThrow().requestedTick()>2,"first live image tolerates a newer desired clock");
                long paused=latest[0]+Math.floorMod(1-latest[0],4);view.playbackClock(paused,0);view.advance(TINY,1);view.view(worldCamera(fixture),40,32);view.advance(TINY,1);
                try(var navigation=view.take(1,view.status().viewRevision()).orElseThrow()) { equal(2L,navigation.info().atlasTick(),"navigation uses last complete live atlas during bounded upload"); }
                until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY&&view.animationStatus().orElseThrow().displayedTick()==paused,()->view.advance(TINY,1));
                try(var stopped=view.take(1,view.status().viewRevision()).orElseThrow()) {
                    equal(paused,stopped.info().atlasTick(),"paused connected view catches up to latest live clock");check(view.materials(2,null,light),"material refresh accepted during playback");
                    until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,2));
                    try(var refreshed=view.take(2,view.status().viewRevision()).orElseThrow()) { equal(paused,refreshed.info().atlasTick(),"material refresh keeps displayed playback receipt");equal(GlAtlasQueue.Phase.IDLE,view.animationUploadStatus().orElseThrow().phase(),"unchanged playback material refresh needs no animation upload"); }
                }
                view.playbackClock(2,0);check(view.submit(3,fixture.retain(),null,light),"new world inherits playback mode and latest clock");
                until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,3));try(var replaced=view.take(3,view.status().viewRevision()).orElseThrow()) { equal(2L,replaced.info().atlasTick(),"replacement first image completes requested live sample"); }
                equal(0x0000ffff,read(first).image().rgba(16,16),"old completed live image survives material and world replacement");
            }
        }
        try(var fixture=WorldGpuFixture.animated(false);var renderer=GlRenderer.create(device);var view=new GlWorldView(renderer,0)) {
            view.view(worldCamera(fixture),32,32);view.playbackClock(2,0);check(view.submit(1,fixture.retain()),"zero-budget live source accepted before animation admission");
            Runnable reject=()-> { try { view.advance(TINY,1); }catch(IllegalStateException expected) { check(expected.getMessage().contains("atlas preparation reservations"),"playback preserves original admission diagnostic"); }check(view.status().phase()!=GlWorldView.Phase.FRAME_READY,"live failure cannot prime with base pixels"); };
            until(()->view.status().phase()==GlWorldView.Phase.FAILED,reject);check(view.take(1,view.status().viewRevision()).isEmpty(),"initial live failure exposes no image");check(view.retryAnimation(1),"failed playback can retry without source upload");until(()->view.status().phase()==GlWorldView.Phase.FAILED,reject);
            var upload=view.uploadStatus();view.playbackClock(0,0);check(view.retryAnimation(1),"failed playback can recover at unchanged pixels under same zero allowance");until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,1));
            try(var recovered=view.take(1,view.status().viewRevision()).orElseThrow()) { equal(0L,recovered.info().atlasTick(),"recovered live frame has truthful base receipt");view.playbackClock(2,0);until(()->view.status().phase()==GlWorldView.Phase.FAILED,reject);equal(0L,recovered.info().atlasTick(),"later playback failure preserves caller-owned frame"); }
            equal(upload,view.uploadStatus(),"playback failures and retry never reupload source geometry");
        }
    }
    private static GlScene finishMaterials(GlUpload job) {
        until(job::ready,()-> { var state=host();var step=job.step(TINY,LIVE);budget(step.transferBytes()+step.copiedBytes(),step.actions(),step.workUnits());check(step.transferBytes()<=65536&&step.copiedBytes()<=65536,"world material copy chunks stay bounded");equal(state,host(),"world material copy restores host bindings"); });
        equal(job.progress().totalBytes(),job.progress().uploadedBytes(),"complete material CPU transfer");equal(job.progress().totalCopyBytes(),job.progress().copiedBytes(),"complete original geometry/atlas GPU copy");return job.take();
    }
    private static long materialCopyBytes(WorldScene scene) { return (long)scene.usage().faces()*MeshPacket.PACKED_BYTES_PER_QUAD+(long)scene.usage().markers()*12+scene.raster().rgbaBytes(); }
    private static void worldMaterialCopies(GlDevice device) {
        for(boolean initiallyLit:new boolean[]{false,true})try(var fixture=WorldGpuFixture.solid(0xffffffff,true);var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,fixture.tint(0xffffff),initiallyLit?fixture.lighting(1,1,1):null);var original=frame(renderer,1,source,null,null,worldCamera(fixture),WorldClip.NONE)) {
            var changed=fixture.tint(0x804020);var light=initiallyLit?null:fixture.lighting(1,0.5f,0.25f);var oldPixels=read(original).image();var oldAllocation=source.allocation;
            try(var job=GlUpload.materials(renderer,source,changed,light);var complete=upload(renderer,fixture,changed,light)) {
                equal(materialCopyBytes(fixture.scene()),job.progress().totalCopyBytes(),"exact immutable source bytes selected for GPU copy");equal((long)fixture.scene().usage().faces()*(light==null?16:64),job.progress().totalBytes(),"only replacement vertex materials use CPU upload");source.close();check(source.closed(),"caller can close source after material admission");failure(GlFailure.Code.STATE,source::cpuScene);
                failure(GlFailure.Code.STATE,()->frame(renderer,2,source,null,null,worldCamera(fixture),WorldClip.NONE));
                try(var refreshed=finishMaterials(job);var reference=frame(renderer,2,complete,null,null,worldCamera(fixture),WorldClip.NONE);var output=frame(renderer,3,refreshed,null,null,worldCamera(fixture),WorldClip.NONE)) {
                    pixels(read(reference).image(),read(output).image());equal(complete.lightingInfo(),refreshed.lightingInfo(),"lighting receipt reflects replacement policy");check(refreshed.cpuScene()==fixture.scene(),"material refresh preserves exact original CPU scene");check(!refreshed.allocation.dependencies.contains(oldAllocation),"completed material scene retains no ancestor GPU journal");
                    var originalLight=initiallyLit?fixture.lighting(1,1,1):null;try(var again=GlUpload.materials(renderer,refreshed,fixture.tint(0xffffff),originalLight)) {
                        var intermediate=refreshed.allocation;refreshed.close();try(var finalScene=finishMaterials(again);var finalFrame=frame(renderer,4,finalScene,null,null,worldCamera(fixture),WorldClip.NONE)) { pixels(oldPixels,read(finalFrame).image());check(!finalScene.allocation.dependencies.contains(intermediate),"repeated material refresh creates no GPU ancestry chain"); }
                    }pixels(oldPixels,read(original).image());
                }
            }
        }
        for(int kind=0;kind<3;kind++)try(var fixture=kind==0?WorldGpuFixture.empty():kind==1?WorldGpuFixture.placeholder():WorldGpuFixture.cutout();var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,null,null);var job=GlUpload.materials(renderer,source,null,fixture.lighting(1,1,1));var copied=finishMaterials(job);var full=upload(renderer,fixture,null,fixture.lighting(1,1,1));var expected=frame(renderer,1,full,null,null,worldCamera(fixture),WorldClip.NONE);var actual=frame(renderer,2,copied,null,null,worldCamera(fixture),WorldClip.NONE)) {
            pixels(read(expected).image(),read(actual).image());equal(materialCopyBytes(fixture.scene()),job.progress().copiedBytes(),"empty, marker and cutout copies cover exact original payload");if(kind==0) { equal(0,copied.allocation.buffers.length,"empty material refresh creates no dummy buffer");equal(0,copied.allocation.textures.length,"empty material refresh creates no dummy texture"); }
        }
        try(var fixture=WorldGpuFixture.translucent();var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,null,null);var job=GlUpload.materials(renderer,source,null,fixture.lighting(1,1,1));var copied=finishMaterials(job);var full=upload(renderer,fixture,null,fixture.lighting(1,1,1))) {
            var camera=worldCamera(fixture);var cpuOrder=WorldDrawOrder.prepare(fixture.scene(),camera.drawView(),LIVE);
            try(var originalPlan=GlDrawPlan.create(source,cpuOrder);var copyPlan=GlDrawPlan.create(copied,cpuOrder);var fullPlan=GlDrawPlan.create(full,cpuOrder);var expected=frame(renderer,1,full,null,fullPlan,camera,WorldClip.NONE);var actual=frame(renderer,2,copied,null,copyPlan,camera,WorldClip.NONE)) { pixels(read(expected).image(),read(actual).image());failure(GlFailure.Code.STALE,()->frame(renderer,3,copied,null,originalPlan,camera,WorldClip.NONE));equal(cpuOrder.size(),actual.info().stats().translucentFaces(),"copied geometry retains every sorted original transparent face"); }
        }
    }
    private static void worldMaterialRecovery(GlDevice device) {
        try(var fixture=WorldGpuFixture.solid(0xffffffff,true);var foreign=WorldGpuFixture.solid(0xffffffff,true);var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,fixture.tint(0xffffff),null)) {
            var tint=fixture.tint(0x804020);var light=fixture.lighting(1,0.5f,0.25f);var before=device.stats();failure(GlFailure.Code.STALE,()->GlUpload.materials(renderer,source,foreign.tint(0xffffff),light));noAdmissionGrowth(before,device.stats());check(!source.closed(),"stale material request leaves borrowed GPU source open");
            var wrong=RenderInput.own(foreign.lighting(1,1,1),RenderMemory.Hold.none());failure(GlFailure.Code.STALE,()->GlUpload.materialsLit(renderer,source,tint,wrong));check(wrong.closed(),"stale supplied lighting wrapper consumed");
            long required=fixture.scene().usage().knownPayloadBytes()+tint.bytes()+light.bytes();
            try(var blocker=denyAdmission(device,required)) { var counters=device.stats();failure(GlFailure.Code.LIMIT,()->GlUpload.materials(renderer,source,tint,light));noAdmissionGrowth(counters,device.stats());check(!source.closed()&&source.cpuScene()==fixture.scene(),"deficient initial admission releases only temporary copy-source owners"); }
            for(var phase:new GlUpload.Phase[]{GlUpload.Phase.READ_FRAMEBUFFER,GlUpload.Phase.TEXTURE_DATA,GlUpload.Phase.VERTEX_DATA,GlUpload.Phase.MATERIAL_DATA,GlUpload.Phase.INDEX_DATA})try(var job=GlUpload.materials(renderer,source,tint,light)) {
                until(()->job.progress().phase()==phase,()->job.step(TINY,LIVE));throwsType(CancellationException.class,()->job.step(TINY,()->true),"material refresh cancellation remains separate from failure");equal(GlUpload.Phase.CANCELLED,job.progress().phase(),"cancelled material refresh is terminal");check(!job.ownsResources(),"cancelled material refresh relinquishes upload/framebuffer/source owners");failure(GlFailure.Code.STATE,job::take);check(!source.closed(),"cancelled refresh preserves borrowed source owner");
            }
            var owned=RenderInput.own(light,RenderMemory.Hold.none());try(var job=GlUpload.materialsLit(renderer,source,tint,owned);var copied=finishMaterials(job)) { check(owned.closed(),"successful refresh consumes lighting owner through destination admission");check(copied.hasNativeLighting(),"owned final lighting enabled on complete copied scene"); }
            try(var queue=new GlUploadQueue(renderer)) {
                check(queue.materials(1,source,tint,light),"queue accepts exact-source material refresh");var old=queue.status();try(var blocker=denyAdmission(device,required)) { failure(GlFailure.Code.LIMIT,()->queue.materials(2,source,tint,light));equal(old,queue.status(),"material queue preserves admitted work through replacement rejection"); }
                until(()->queue.status().phase()==GlUploadQueue.Phase.READY,()->queue.advance(TINY,1));try(var copied=queue.take(1).orElseThrow()) { check(copied.scene().cpuScene()==fixture.scene(),"queued material transfer keeps original CPU provenance"); }
            }
            try(var view=new GlWorldView(renderer)) {
                view.view(worldCamera(fixture),32,32);check(view.submit(1,fixture.retain(),fixture.tint(0xffffff),null),"connected material fixture accepted");until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,1));
                try(var oldFrame=view.take(1,view.status().viewRevision()).orElseThrow();var complete=upload(renderer,fixture,tint,light);var reference=frame(renderer,3,complete,null,null,worldCamera(fixture),WorldClip.NONE)) {
                    var oldPixels=read(oldFrame).image();var old=view.status();try(var blocker=denyAdmission(device,required)) { failure(GlFailure.Code.LIMIT,()->view.materials(2,tint,light));equal(old,view.status(),"connected material admission rejection preserves world/view tokens"); }
                    check(view.materials(2,tint,light),"connected material refresh accepts unspent replacement token");check(!view.materials(2,tint,light),"duplicate material token is ignored");until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,2));
                    try(var current=view.take(2,view.status().viewRevision()).orElseThrow()) { pixels(read(reference).image(),read(current).image());pixels(oldPixels,read(oldFrame).image());equal(materialCopyBytes(fixture.scene()),view.uploadStatus().progress().orElseThrow().copiedBytes(),"connected view routes geometry/atlas through GPU copy"); }
                }
            }
        }
    }
    private static void drawPlanRebinding(GlDevice device) {
        try(var fixture=WorldGpuFixture.translucent();var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,null,fixture.lighting(1,1,1))) {
            var camera=worldCamera(fixture);var cpu=WorldDrawOrder.prepare(fixture.scene(),camera.drawView(),LIVE);check(!cpu.requiresPlaceholder(),"complete source order prepared");var ancestor=source.allocation;
            try(var original=GlDrawPlan.create(source,cpu);var copy=GlUpload.materials(renderer,source,null,fixture.lighting(0.5f,1,0.25f))) {
                source.close();try(var destination=finishMaterials(copy)) {
                    var before=device.stats();var state=host();
                    try(var rebound=original.rebind(destination)) {
                        equal(state,host(),"order rebind preserves host GL state");equal(before.cpuPayloadBytes()+cpu.bytes(),device.stats().cpuPayloadBytes(),"rebind independently charges complete order payload");check(rebound.order(destination,camera)==cpu,"rebind shares immutable exact CPU order without cloning or sorting");
                        original.close();until(()->ancestor.released,device::collect);check(ancestor.released,"rebound plan retains no ancestor GPU allocation");
                        var fresh=WorldDrawOrder.prepare(fixture.scene(),camera.drawView(),LIVE);check(Arrays.equals(cpu.copyOrder(),fresh.copyOrder()),"rebound source keys match independently prepared order");
                        try(var full=upload(renderer,fixture,null,fixture.lighting(0.5f,1,0.25f));var referenceOrder=GlDrawPlan.create(full,fresh);var expected=frame(renderer,1,full,null,referenceOrder,camera,WorldClip.NONE);var actual=frame(renderer,2,destination,null,rebound,camera,WorldClip.NONE)) { pixels(read(expected).image(),read(actual).image()); }
                        try(var nextCopy=GlUpload.materials(renderer,destination,null,null)) {
                            var intermediate=destination.allocation;destination.close();try(var next=finishMaterials(nextCopy);var again=rebound.rebind(next)) {
                                check(again.order(next,camera)==cpu,"repeated material copy reuses original immutable order");rebound.close();until(()->intermediate.released,device::collect);check(intermediate.released,"repeated rebinding creates no GPU ancestry chain");
                                try(var output=frame(renderer,3,next,null,again,camera,WorldClip.NONE)) { equal(fixture.scene().usage().translucentFaces(),output.info().stats().translucentFaces(),"rebound plan submits all original transparent faces"); }
                            }
                        }
                    }
                }
            }
        }
        try(var fixture=WorldGpuFixture.translucent();var other=WorldGpuFixture.translucent();var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,null,null);var destination=upload(renderer,fixture,null,null);var wrong=upload(renderer,other,null,null)) {
            var camera=worldCamera(fixture);var cpu=WorldDrawOrder.prepare(fixture.scene(),camera.drawView(),LIVE);
            try(var original=GlDrawPlan.create(source,cpu)) {
                var before=device.stats();var state=host();failure(GlFailure.Code.STALE,()->original.rebind(wrong));equal(before,device.stats(),"equal-valued but different CPU scene rejects before admission");equal(state,host(),"wrong-scene rebind preserves host state");
                try(var blocker=denyAdmission(device,cpu.bytes())) { var blocked=device.stats();failure(GlFailure.Code.LIMIT,()->original.rebind(destination));noAdmissionGrowth(blocked,device.stats());check(original.order(source,camera)==cpu,"rebind admission failure preserves source plan"); }
                try(var rebound=original.rebind(destination)) {
                    var staleCamera=new GlCamera(camera.anchor(),camera.eyeX()+0.25,camera.eyeY(),camera.eyeZ(),camera.targetX(),camera.targetY(),camera.targetZ(),camera.fieldOfView(),camera.near(),camera.far());failure(GlFailure.Code.STALE,()->rebound.order(destination,staleCamera));
                    try(var foreign=new GlDevice(device.limits(),device.memory().orElseThrow());var foreignRenderer=GlRenderer.create(foreign);var foreignScene=upload(foreignRenderer,fixture,null,null)) { var counters=device.stats();failure(GlFailure.Code.STALE,()->original.rebind(foreignScene));equal(counters,device.stats(),"foreign device rebind creates no source journal"); }
                }
                destination.close();failure(GlFailure.Code.STATE,()->original.rebind(destination));original.close();failure(GlFailure.Code.STATE,()->original.rebind(source));
            }
        }
        try(var fixture=WorldGpuFixture.empty();var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,null,null);var destination=upload(renderer,fixture,null,null)) {
            var camera=worldCamera(fixture);var cpu=WorldDrawOrder.prepare(fixture.scene(),camera.drawView(),LIVE);try(var original=GlDrawPlan.create(source,cpu);var rebound=original.rebind(destination)) { equal(0,rebound.order(destination,camera).size(),"empty exact-scene order rebinds without inventing primitives"); }
        }
    }
    private static void materialOrderReuse(GlDevice device) {
        for(boolean owned:new boolean[]{false,true})try(var fixture=WorldGpuFixture.translucent();var renderer=GlRenderer.create(device);var view=new GlWorldView(renderer)) {
            var camera=worldCamera(fixture);var light=fixture.lighting(0.5f,1,0.25f);view.view(camera,32,32);check(view.submit(1,fixture.retain(),null,fixture.lighting(1,1,1)),"initial transparent world accepted");until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,1));
            try(var saved=view.take(1,view.status().viewRevision()).orElseThrow()) {
                var originalPixels=read(saved).image();until(()->view.orderReservedBytes()==0&&view.animationReservedBytes()==0,Thread::yield);long required=fixture.scene().usage().knownPayloadBytes()+light.bytes();var status=view.status();var ordering=view.orderStatus();
                try(var blocker=denyAdmission(device,required)) { failure(GlFailure.Code.LIMIT,()->view.materials(2,null,light));equal(status,view.status(),"rejected material refresh preserves current transparent frame token");equal(ordering,view.orderStatus(),"rejected material refresh preserves complete order worker receipt"); }
                if(owned) { var input=trackedLighting(device,light);check(view.materialsLit(2,null,input)&&input.closed(),"owned transparent material refresh accepted"); }else check(view.materials(2,null,light),"transparent material refresh accepted");
                var retained=view.orderStatus();check(retained.phase()==WorldOrderWorker.Phase.IDLE,"accepted material change invalidates old worker token");
                var projection=new GlCamera(camera.anchor(),camera.eyeX(),camera.eyeY(),camera.eyeZ(),camera.targetX(),camera.targetY(),camera.targetZ(),camera.fieldOfView()+10,camera.near(),camera.far());var clip=new WorldClip(fixture.scene().snapshot().extent());view.view(projection,40,32);view.clip(clip);
                until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()-> { view.advance(TINY,2);equal(retained,view.orderStatus(),"material refresh, projection/size and clip preserve admitted complete order without a new sort"); });
                try(var current=view.take(2,view.status().viewRevision()).orElseThrow();var reference=upload(renderer,fixture,null,light);var order=GlDrawPlan.create(reference,WorldDrawOrder.prepare(fixture.scene(),projection.drawView(),LIVE));var expected=GlFrame.render(renderer,3,reference,null,order,projection,40,32,clip,LIVE)) { pixels(read(expected).image(),read(current).image());equal(saved.info().source(),current.info().source(),"order reuse preserves original native source receipt"); }
                check(view.materials(3,null,light),"second transparent material refresh accepted");var moved=new GlCamera(camera.anchor(),camera.eyeX()+0.25,camera.eyeY(),camera.eyeZ(),camera.targetX(),camera.targetY(),camera.targetZ(),camera.fieldOfView(),camera.near(),camera.far());view.view(moved,32,32);long invalidated=view.orderStatus().revision();
                until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,3));check(view.orderStatus().revision()>invalidated&&view.orderStatus().phase()==WorldOrderWorker.Phase.DELIVERED,"camera pose change during material upload discards reuse and sorts exact new pose");view.take(3,view.status().viewRevision()).orElseThrow().close();
                check(view.submit(4,fixture.retain(),null,light),"full world replacement accepted with equal CPU source");long replacement=view.orderStatus().revision();until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,4));check(view.orderStatus().revision()>replacement,"full replacement uses ordinary order preparation");view.take(4,view.status().viewRevision()).orElseThrow().close();
                check(view.materials(5,null,light),"pending transparent refresh retained for invalidation");check(view.invalidate(6),"new world invalidates pending material order");check(view.submit(6,fixture.retain(),null,light),"replacement after material-order invalidation accepted");long reset=view.orderStatus().revision();until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,6));check(view.orderStatus().revision()>reset,"invalidated material order is never rebound to replacement world");view.take(6,view.status().viewRevision()).orElseThrow().close();
                check(view.materials(7,null,light),"shutdown starts with an owned pending material order");view.close();until(()->view.orderReservedBytes()==0&&view.animationReservedBytes()==0,Thread::yield);pixels(originalPixels,read(saved).image());
            }
        }
    }
    private static void materialOrderRecovery(GlDevice device) {
        try(var fixture=WorldGpuFixture.translucent();var renderer=GlRenderer.create(device);var view=new GlWorldView(renderer)) {
            var camera=worldCamera(fixture);var light=fixture.lighting(1,0.5f,0.25f);view.view(camera,32,32);check(view.submit(1,fixture.retain(),null,light),"transparent recovery source accepted");until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,1));
            try(var saved=view.take(1,view.status().viewRevision()).orElseThrow()) {
                var originalPixels=read(saved).image();view.clock(2,0);check(view.materials(2,null,light),"recovery material refresh accepted");var retained=view.orderStatus();
                // An explicit static clock leaves initial metadata observation for the next advance,
                // exposing a complete source before its new order journal is admitted.
                until(()->view.uploadStatus().phase()==GlUploadQueue.Phase.DELIVERED,()->view.advance(TINY,2));equal(GlWorldView.Phase.WAITING_VIEW,view.status().phase(),"complete material source waits for initial clock observation");
                until(()->view.orderReservedBytes()==0&&view.animationReservedBytes()==0,Thread::yield);long bytes=(long)fixture.scene().usage().translucentFaces()*Long.BYTES;
                try(var blocker=denyAdmission(device,bytes)) {
                    var state=host();failure(GlFailure.Code.LIMIT,()->view.advance(TINY,2));equal(state,host(),"failed order rebind leaves host state intact");equal(GlWorldView.Phase.FAILED,view.status().phase(),"rebind admission failure is explicit");check(view.take(2,view.status().viewRevision()).isEmpty(),"failed rebound order exposes no partial frame");equal(retained,view.orderStatus(),"rebind rejection does not schedule replacement sort");
                }
                var uploaded=view.uploadStatus();check(!view.retryUpload(2),"complete material source does not use source-upload retry");view.redraw();until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,2));equal(retained,view.orderStatus(),"redraw retries retained order admission without sorting");equal(uploaded,view.uploadStatus(),"order retry never reuploads material geometry");
                try(var recovered=view.take(2,view.status().viewRevision()).orElseThrow()) { pixels(originalPixels,read(recovered).image()); }
                pixels(originalPixels,read(saved).image());
            }
        }
        try(var fixture=WorldGpuFixture.translucent();var renderer=GlRenderer.create(device);var view=new GlWorldView(renderer)) {
            check(view.submit(1,fixture.retain()),"camera-less world source accepted");until(()->view.uploadStatus().phase()==GlUploadQueue.Phase.DELIVERED,()->view.advance(TINY,1));
            check(view.materials(2,null,null),"material refresh needs no existing order before first camera");var idle=view.orderStatus();view.view(worldCamera(fixture),32,32);until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,2));check(view.orderStatus().revision()>idle.revision()&&view.orderStatus().phase()==WorldOrderWorker.Phase.DELIVERED,"missing prior complete order uses ordinary asynchronous preparation");view.take(2,view.status().viewRevision()).orElseThrow().close();
        }
    }
    private static long changedAtlasBytes(WorldScene scene,AtlasRaster raster) {
        long bytes=0;for(int page=0;page<raster.pages().size();page++)if(raster.pages().get(page)!=scene.raster().pages().get(page))bytes+=raster.pages().get(page).bytes();return bytes;
    }
    private static void displayedMaterialAtlas(GlDevice device) {
        for(boolean interpolate:new boolean[]{false,true})try(var fixture=WorldGpuFixture.animated(interpolate);var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,null,fixture.lighting(1,1,1));var base=GlAtlasFrame.capture(source);var displayed=update(base,interpolate?1:2);var original=frame(renderer,1,source,displayed,null,worldCamera(fixture),WorldClip.NONE)) {
            var actual=displayed.raster();var light=fixture.lighting(1,1,1);long extra=changedAtlasBytes(fixture.scene(),actual);check(extra>0,"animated raster has separately retained changed pages");var originalPixels=read(original).image();var ancestor=source.allocation;
            try(var job=GlUpload.materials(renderer,source,displayed,null,light)) {
                long cpu=fixture.scene().usage().knownPayloadBytes()+light.bytes()+extra;
                displayed.close();source.close();failure(GlFailure.Code.STATE,displayed::raster);failure(GlFailure.Code.STATE,source::raster);
                try(var copied=finishMaterials(job);var captured=GlAtlasFrame.capture(copied);var output=frame(renderer,2,copied,null,null,worldCamera(fixture),WorldClip.NONE)) {
                    equal(cpu,copied.allocation.cpu,"destination accounts for exact source, lighting and changed atlas pages");check(copied.raster()==actual&&captured.raster()==actual,"copied GPU scene and captured atlas preserve exact displayed raster");equal(0L,copied.cpuScene().raster().tick(),"native CPU scene base raster is not rewritten");equal(actual.tick(),output.info().atlasTick(),"default frame receipt uses copied atlas clock");pixels(originalPixels,read(output).image());check(!copied.allocation.dependencies.contains(ancestor),"displayed-atlas refresh has no GPU scene ancestry");
                    var intermediate=copied.allocation;try(var again=GlUpload.materials(renderer,copied,null,light)) {
                        copied.close();try(var finalScene=finishMaterials(again);var finalFrame=frame(renderer,3,finalScene,null,null,worldCamera(fixture),WorldClip.NONE)) { check(finalScene.raster()==actual,"ordinary material refresh inherits an already animated scene base");equal(actual.tick(),finalFrame.info().atlasTick(),"repeated refresh does not reset clock");pixels(originalPixels,read(finalFrame).image());check(!finalScene.allocation.dependencies.contains(intermediate),"repeated displayed-atlas refresh creates no source chain"); }
                    }pixels(originalPixels,read(original).image());
                }
            }
        }
    }
    private static void displayedAtlasRecovery(GlDevice device) {
        try(var fixture=WorldGpuFixture.animated(true);var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,null,null);var base=GlAtlasFrame.capture(source);var displayed=update(base,1);var equivalent=upload(renderer,fixture,null,null);var wrong=GlAtlasFrame.capture(equivalent);var queue=new GlUploadQueue(renderer)) {
            var light=fixture.lighting(1,1,1);var raster=displayed.raster();long extra=changedAtlasBytes(fixture.scene(),raster),required=fixture.scene().usage().knownPayloadBytes()+light.bytes()+extra;
            var before=device.stats();failure(GlFailure.Code.STALE,()->GlUpload.materials(renderer,source,wrong,null,light));noAdmissionGrowth(before,device.stats());check(equivalent.cpuScene()==source.cpuScene(),"wrong atlas fixture shares CPU identity but differs in GPU ownership");
            check(queue.materials(1,source,displayed,null,light),"queue accepts displayed atlas snapshot");var accepted=queue.status();var input=trackedLighting(device,light);
            try(var blocker=denyAdmission(device,required)) { var counters=device.stats();failure(GlFailure.Code.LIMIT,()->queue.materialsLit(2,source,displayed,null,input));check(input.closed(),"displayed-atlas admission rejection consumes fresh lighting");equal(accepted,queue.status(),"changed-page overlap rejection preserves accepted copy");noAdmissionGrowth(counters,device.stats()); }
            var stale=trackedLighting(device,light);failure(GlFailure.Code.STALE,()->queue.materialsLit(2,source,wrong,null,stale));check(stale.closed(),"wrong GPU atlas consumes fresh lighting");equal(accepted,queue.status(),"wrong atlas preserves pending queue");
            try(var cancelled=GlUpload.materials(renderer,source,displayed,null,light)) { until(()->cancelled.progress().phase()==GlUpload.Phase.TEXTURE_DATA,()->cancelled.step(TINY,LIVE));throwsType(CancellationException.class,()->cancelled.step(TINY,()->true),"displayed atlas copy cancellation");check(!cancelled.ownsResources(),"cancelled copy relinquishes both source owners and temporary framebuffer");check(displayed.raster()==raster&&!source.closed(),"cancelled copy preserves caller source and atlas"); }
            until(()->queue.status().phase()==GlUploadQueue.Phase.READY,()->queue.advance(TINY,1));try(var copied=queue.take(1).orElseThrow();var expected=frame(renderer,1,source,displayed,null,worldCamera(fixture),WorldClip.NONE);var actual=frame(renderer,2,copied.scene(),null,null,worldCamera(fixture),WorldClip.NONE)) { pixels(read(expected).image(),read(actual).image());equal(raster.tick(),actual.info().atlasTick(),"queued displayed raster clock"); }
            var supplied=trackedLighting(device,light);check(queue.materialsLit(2,source,displayed,null,supplied)&&supplied.closed(),"owned queued refresh accepts displayed pixels");until(()->queue.status().phase()==GlUploadQueue.Phase.READY,()->queue.advance(TINY,2));queue.take(2).orElseThrow().close();
            displayed.close();var closedInput=trackedLighting(device,light);failure(GlFailure.Code.STATE,()->GlUpload.materialsLit(renderer,source,displayed,null,closedInput));check(closedInput.closed()&&!source.closed(),"closed atlas rejection consumes only fresh lighting");
        }
    }
    private static RenderInput<WorldLighting> trackedLighting(GlDevice device,WorldLighting lighting) {
        return RenderInput.own(lighting,device.memory().orElseThrow().hold("Owned world material lighting",lighting.bytes()));
    }
    private static void worldOwnedMaterials(GlDevice device) {
        try(var fixture=WorldGpuFixture.solid(0xffffffff,true);var foreign=WorldGpuFixture.solid(0xffffffff,true);var renderer=GlRenderer.create(device);var source=upload(renderer,fixture,fixture.tint(0xffffff),null);var queue=new GlUploadQueue(renderer)) {
            var tint=fixture.tint(0x804020);var light=fixture.lighting(1,0.5f,0.25f);long required=fixture.scene().usage().knownPayloadBytes()+tint.bytes()+light.bytes();var memory=device.memory().orElseThrow();
            long before=memory.stats().chargedBytes();var input=trackedLighting(device,light);check(queue.materialsLit(1,source,tint,input),"owned material queue accepts fresh lighting");check(input.closed()&&!source.closed(),"queue consumes lighting while borrowing the GPU source");equal(before+required,memory.stats().chargedBytes(),"new device journal replaces external lighting charge after admission");var accepted=queue.status();
            var duplicate=trackedLighting(device,light);check(!queue.materialsLit(1,source,tint,duplicate)&&duplicate.closed(),"duplicate queue token consumes only unused fresh lighting");equal(accepted,queue.status(),"duplicate owned materials preserve active queue status");
            throwsType(IllegalStateException.class,()->queue.materialsLit(2,source,tint,input),"closed lighting wrapper cannot be reused");equal(accepted,queue.status(),"closed wrapper rejection preserves accepted materials");
            var limited=trackedLighting(device,light);try(var blocker=denyAdmission(device,required)) { var counters=device.stats();failure(GlFailure.Code.LIMIT,()->queue.materialsLit(2,source,tint,limited));check(limited.closed(),"deficient admission consumes fresh tracked lighting");equal(accepted,queue.status(),"owned lighting remains charged until destination admission, preserving pending work");noAdmissionGrowth(counters,device.stats()); }
            var stale=trackedLighting(device,foreign.lighting(1,1,1));failure(GlFailure.Code.STALE,()->queue.materialsLit(2,source,tint,stale));check(stale.closed(),"wrong-scene owned lighting consumed");equal(accepted,queue.status(),"stale owned materials preserve accepted queue");
            try(var unrelated=new RenderMemory(new RenderMemory.Limits(4096,2))) {
                var foreignInput=RenderInput.own(light,unrelated.hold("Foreign world lighting",light.bytes()));throwsType(IllegalArgumentException.class,()->queue.materialsLit(2,source,tint,foreignInput),"owned material queue rejects foreign coordinator");check(foreignInput.closed(),"foreign lighting wrapper consumed");equal(0L,unrelated.stats().chargedBytes(),"foreign lighting charge released");equal(accepted,queue.status(),"foreign coordinator cannot change queue state");
            }
            until(()->queue.status().phase()==GlUploadQueue.Phase.READY,()->queue.advance(TINY,1));try(var copied=queue.take(1).orElseThrow()) { check(copied.scene().hasNativeLighting()&&copied.scene().cpuScene()==fixture.scene(),"owned queued materials preserve final-lighting and native receipts"); }
            queue.close();var closed=trackedLighting(device,light);failure(GlFailure.Code.STATE,()->queue.materialsLit(2,source,tint,closed));check(closed.closed()&&!source.closed(),"closed queue consumes fresh lighting and preserves borrowed scene");
            try(var view=new GlWorldView(renderer)) {
                view.view(worldCamera(fixture),32,32);check(view.submit(1,fixture.retain(),fixture.tint(0xffffff),null),"connected owned-material fixture accepted");until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,1));
                try(var oldFrame=view.take(1,view.status().viewRevision()).orElseThrow();var full=upload(renderer,fixture,tint,light);var expected=frame(renderer,3,full,null,null,worldCamera(fixture),WorldClip.NONE)) {
                    var oldPixels=read(oldFrame).image();var prior=view.status();var limitedView=trackedLighting(device,light);
                    try(var blocker=denyAdmission(device,required)) { failure(GlFailure.Code.LIMIT,()->view.materialsLit(2,tint,limitedView));check(limitedView.closed(),"view consumes rejected owned lighting");equal(prior,view.status(),"view tokens remain unchanged through overlapping owned-light admission rejection"); }
                    var supplied=trackedLighting(device,light);check(view.materialsLit(2,tint,supplied)&&supplied.closed(),"view accepts and consumes tracked final lighting");var waiting=view.status();
                    var duplicateView=trackedLighting(device,light);check(!view.materialsLit(2,tint,duplicateView)&&duplicateView.closed(),"duplicate connected material token consumes unused lighting");equal(waiting,view.status(),"duplicate connected token preserves copy progress");
                    var early=trackedLighting(device,light);failure(GlFailure.Code.STATE,()->view.materialsLit(3,tint,early));check(early.closed(),"incomplete-source rejection consumes future lighting");equal(waiting,view.status(),"incomplete-source rejection preserves current copy");
                    until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()->view.advance(TINY,2));try(var current=view.take(2,view.status().viewRevision()).orElseThrow()) {
                        pixels(read(expected).image(),read(current).image());equal(WorldGpuFixture.LIGHTING,current.info().lighting().policy(),"owned connected lighting policy preserved");view.close();var afterClose=trackedLighting(device,light);failure(GlFailure.Code.STATE,()->view.materialsLit(3,tint,afterClose));check(afterClose.closed(),"closed view consumes supplied lighting");pixels(oldPixels,read(oldFrame).image());pixels(read(expected).image(),read(current).image());
                    }
                }
            }
        }
    }
    private static void worldClockPriming(GlDevice device) {
        for(boolean interpolate:new boolean[]{false,true})try(var fixture=WorldGpuFixture.animated(interpolate);var renderer=GlRenderer.create(device);var view=new GlWorldView(renderer)) {
            var light=fixture.lighting(1,1,1);view.view(worldCamera(fixture),32,32);view.clock(2,0);check(view.submit(1,fixture.retain(),null,light),"initial animated world with a nonzero clock accepted");
            until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()-> { var state=view.advance(TINY,1);check(state.phase()!=GlWorldView.Phase.FAILED,"initial atlas clock preparation succeeds"); });
            try(var first=view.take(1,view.status().viewRevision()).orElseThrow()) {
                equal(2L,first.info().atlasTick(),"first published world uses the requested animated clock");equal(0x0000ffff,read(first).image().rgba(16,16),"first world frame is blue without a tick-zero red frame");
                if(interpolate) { var supplied=trackedLighting(device,light);check(view.materialsLit(2,null,supplied)&&supplied.closed(),"owned material refresh retains displayed animation clock"); }
                else check(view.materials(2,null,light),"material refresh retains external animation clock");
                until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()-> { var state=view.advance(TINY,2);check(state.phase()!=GlWorldView.Phase.FAILED,"refreshed atlas clock preparation succeeds"); });
                try(var refreshed=view.take(2,view.status().viewRevision()).orElseThrow()) {
                    equal(2L,refreshed.info().atlasTick(),"first refreshed material frame has requested clock");pixels(read(first).image(),read(refreshed).image());
                    equal(GlAtlasQueue.Phase.IDLE,view.animationUploadStatus().orElseThrow().phase(),"displayed atlas material copy avoids a second animation upload");until(()->view.animationReservedBytes()==0,Thread::yield);
                    view.clock(1,0);check(view.submit(3,fixture.retain(),null,light),"replacement world accepts a new initial clock");
                    until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()-> { var state=view.advance(TINY,3);check(state.phase()!=GlWorldView.Phase.FAILED,"replacement atlas clock preparation succeeds"); });
                    try(var replacement=view.take(3,view.status().viewRevision()).orElseThrow()) { equal(interpolate?0x800080ff:0xff0000ff,read(replacement).image().rgba(16,16),"unchanged scheduled pixels or interpolated midpoint can both complete initial presentation"); }
                    equal(0x0000ffff,read(first).image().rgba(16,16),"saved first frame survives later clock/source replacements");equal(0x0000ffff,read(refreshed).image().rgba(16,16),"saved refreshed frame remains immutable");
                }
            }
        }
        try(var fixture=WorldGpuFixture.animated(false);var renderer=GlRenderer.create(device);var view=new GlWorldView(renderer,0)) {
            view.view(worldCamera(fixture),32,32);view.clock(2,0);check(view.submit(1,fixture.retain()),"initial world accepted before animation worker admission");
            Runnable rejectedAdvance=()-> {
                try { view.advance(TINY,1); }catch(IllegalStateException expected) { check(expected.getMessage()!=null&&expected.getMessage().contains("atlas preparation reservations"),"required initial atlas preserves its worker admission diagnostic"); }
                check(view.status().phase()!=GlWorldView.Phase.FRAME_READY,"failed initial animation never publishes the base atlas as current");
            };
            until(()->view.status().phase()==GlWorldView.Phase.FAILED,rejectedAdvance);
            check(view.take(1,view.status().viewRevision()).isEmpty(),"initial animation admission failure exposes no incorrect frame");equal(GlWorldAnimation.Phase.FAILED,view.animationStatus().orElseThrow().phase(),"animation diagnostics remain available");
            var failed=view.status();var upload=view.uploadStatus();check(!view.retryAnimation(2),"stale animation retry cannot alter current view");equal(failed,view.status(),"stale retry preserves failure evidence");throwsType(IllegalArgumentException.class,()->view.retryAnimation(0),"invalid animation retry token rejected");check(!view.retryUpload(1),"complete source uses animation retry rather than source-upload retry");
            check(view.retryAnimation(1),"failed current animation explicitly re-armed");equal(failed.viewRevision()+1,view.status().viewRevision(),"animation retry advances only the view token");var pending=view.status();check(!view.retryAnimation(1),"pending animation retry cannot be duplicated");equal(pending,view.status(),"duplicate retry preserves pending status");until(()->view.status().phase()==GlWorldView.Phase.FAILED,rejectedAdvance);equal(upload,view.uploadStatus(),"rejected animation retry never reuploads geometry");
            view.clock(0,0);check(view.retryAnimation(1),"changed requested clock can retry against the complete source");until(()->view.status().phase()==GlWorldView.Phase.FRAME_READY,()-> { var state=view.advance(TINY,1);check(state.phase()!=GlWorldView.Phase.FAILED,"unchanged atlas pixels recover within the original zero allowance"); });
            try(var recovered=view.take(1,view.status().viewRevision()).orElseThrow()) { equal(0L,recovered.info().atlasTick(),"recovered clock receipt");equal(fixture.scene().snapshot().source(),recovered.info().source(),"animation retry retains native source identity"); }
            equal(upload,view.uploadStatus(),"successful animation retry preserves completed scene upload");equal(0L,view.animationReservedBytes(),"unchanged-pixel retry needs no worker payload");check(!view.retryAnimation(1),"healthy animation cannot be retried");
        }
    }
    private static void worldMaterials(GlDevice device) {
        try(var fixture=WorldGpuFixture.solid(0xffffffff,true);var renderer=GlRenderer.create(device)) {
            var owner=fixture.retain();var before=device.stats();failure(GlFailure.Code.MATERIAL,()->GlUpload.begin(renderer,owner));check(owner.closed(),"missing tint consumes only fresh CPU owner");equal(before,device.stats(),"missing required tint allocates no journal");var tint=fixture.tint(0x804020);var lighting=fixture.lighting(1,0.5f,0.25f);
            try(var foreign=new RenderMemory(new RenderMemory.Limits(lighting.bytes(),1))) { var wrongOwner=fixture.retain();var input=RenderInput.own(lighting,foreign.hold("Foreign final lighting",lighting.bytes()));var saved=device.stats();throwsType(IllegalArgumentException.class,()->GlUpload.beginLit(renderer,wrongOwner,tint,input),"foreign lighting coordinator rejected");check(wrongOwner.closed()&&input.closed(),"foreign lighting rejection consumes fresh scene and lighting owners");equal(saved,device.stats(),"foreign lighting rejection allocates no GPU journal");equal(0L,foreign.stats().chargedBytes(),"foreign lighting input charge released"); }
            try(var scene=upload(renderer,fixture,tint,lighting);var state=new GlState(device);var transfer=new GlTransferState(device);var pack=new GlPackState(device)) {
                glEnable(GL_BLEND);glEnable(GL_SCISSOR_TEST);glEnable(GL_RASTERIZER_DISCARD);glViewport(2,3,5,7);glScissor(2,3,4,5);glColorMask(false,false,true,false);glDepthMask(false);glActiveTexture(GL_TEXTURE3);glPixelStorei(GL_PACK_ALIGNMENT,8);glPixelStorei(GL_PACK_ROW_LENGTH,40);glPixelStorei(GL_PACK_SKIP_ROWS,1);var host=host();
                try(var output=frame(renderer,1,scene,null,null,worldCamera(fixture),WorldClip.NONE)) {
                    equal(host,host(),"world material drawing restores host state");var image=read(output).image();int rgba=image.rgba(16,16);int[] expected={128,32,8};for(int channel=0;channel<3;channel++)check(Math.abs(expected[channel]-(rgba>>>(24-channel*8)&255))<=1,"separate tint/final lighting multiplication");equal(255,rgba&255,"opaque material alpha");check(output.info().lighting().supplied(),"completed frame records supplied final lighting");equal(WorldGpuFixture.LIGHTING,output.info().lighting().policy(),"actual supplied lighting policy retained");equal(host,host(),"world readback restores host pack/draw state");
                }
                try(var mismatch=WorldGpuFixture.solid(0xffffffff,true)) { var wrong=mismatch.retain();before=device.stats();failure(GlFailure.Code.STALE,()->GlUpload.begin(renderer,wrong,tint,lighting));check(wrong.closed(),"stale material capture consumes fresh unrelated owner");equal(before,device.stats(),"stale scene-bound tint/lighting reject without new allocation"); }
            }
        }
    }
    private static void worldDiagnostics(GlDevice device) {
        try(var fixture=WorldGpuFixture.neighbors();var renderer=GlRenderer.create(device)) {
            var owner=fixture.retain();try(var job=GlUpload.begin(renderer,owner,null,fixture.lighting(1,1,1))) { until(()->job.progress().phase()==GlUpload.Phase.VERTEX_DATA,()->job.step(TINY,LIVE));throwsType(CancellationException.class,()->job.step(TINY,()->true),"partially stored world upload cancels");equal(GlUpload.Phase.CANCELLED,job.progress().phase(),"world upload cancellation phase");failure(GlFailure.Code.STATE,job::take); }glFinish();device.collect();check(owner.closed(),"world cancellation retires GPU storage and releases its CPU lease");
        }
        try(var empty=WorldGpuFixture.empty();var marker=WorldGpuFixture.placeholder();var renderer=GlRenderer.create(device);var noGeometry=upload(renderer,empty,null,null);var diagnostic=upload(renderer,marker,null,null);var clear=frame(renderer,1,noGeometry,null,null,worldCamera(empty),WorldClip.NONE);var output=frame(renderer,2,diagnostic,null,null,worldCamera(marker),WorldClip.NONE);var index=WorldPickIndex.prepare(output.retainCpuScene(),LIVE)) {
            equal(0,noGeometry.allocation.textures.length,"empty scene invents no texture");equal(0,noGeometry.allocation.buffers.length,"empty scene invents no mesh");equal(0L,clear.info().stats().triangleIndices(),"empty world submits no triangles");equal(1,output.info().stats().markerInstances(),"unsupported original state draws one explicit marker");equal(0L,output.info().stats().triangleIndices(),"marker does not fabricate block faces");var image=read(output).image();boolean magenta=false;for(int y=0;y<image.height();y++)for(int x=0;x<image.width();x++)magenta|=image.rgba(x,y)==0xff00ccff;check(magenta,"diagnostic wireframe has visible explicit magenta pixels");var hit=index.query(output.info().ray(16.5,16.5),WorldPickIndex.Options.DEFAULT,output.raster(),LIVE).hit().orElseThrow();check(hit.placeholder()&&!hit.visualProblems().isEmpty(),"diagnostic picking exposes original visual problem");equal(marker.scene().snapshot().states().getFirst(),hit.state(),"marker preserves original native state");
            try(var queue=new GlFrameQueue(renderer)) {
                var request=new GlFrameQueue.Request(diagnostic,worldCamera(marker),32,32);check(queue.render(1,request,LIVE),"world completed frame queued");var ready=queue.status();var invalid=new GlFrameQueue.Request(diagnostic,worldCamera(marker),65,32);failure(GlFailure.Code.LIMIT,()->queue.render(2,invalid,LIVE));equal(ready,queue.status(),"invalid world replacement preserves untaken READY frame");
                try(var retained=queue.take(1).orElseThrow()) { throwsType(CancellationException.class,()->queue.render(2,request,()->true),"obsolete world draw cancelled");check(queue.take(2).isEmpty(),"cancelled world draw exposes no partial frame");check(queue.invalidate(3)&&queue.render(3,request,LIVE),"world frame queue recovers after cancellation");try(var recovered=queue.take(3).orElseThrow()) { queue.close();equal(1L,retained.info().revision(),"old transferred frame keeps original revision through queue closure");equal(3L,recovered.info().revision(),"recovered transferred world frame remains independent");pixels(read(retained).image(),read(recovered).image()); } }
            }
        }
    }
    private static void worldRangeCulling(GlDevice device) {
        for(boolean mixed:new boolean[]{false,true})try(var fixture=WorldGpuFixture.culling(mixed);var renderer=GlRenderer.create(device);var scene=upload(renderer,fixture,null,fixture.lighting(1,1,1))) {
            var origin=fixture.scene().snapshot().extent().position(0);var camera=new GlCamera(origin,16,18,48,16,0.5,28,60,0.1,128);var clip=new WorldClip(new WorldSnapshot.Extent(origin.x(),origin.y(),Math.addExact(origin.z(),24),32,1,8));var cpuOrder=WorldDrawOrder.prepare(fixture.scene(),camera.drawView(),LIVE);check(!cpuOrder.requiresPlaceholder(),"complete sorted culling fixture");
            try(var order=GlDrawPlan.create(scene,cpuOrder)) {
                long beforeCpu=device.stats().cpuPayloadBytes();equal(1093L,renderer.drawScratchBytes(scene,true),"metadata-only world draw scratch size");
                try(var optimized=GlFrame.render(renderer,1,scene,null,order,camera,32,32,clip,true,LIVE);var exhaustive=GlFrame.render(renderer,2,scene,null,order,camera,32,32,clip,false,LIVE);var reference=GlFrame.render(renderer,3,scene,null,order,camera,32,32,clip,GlCullingMode.LINEAR,LIVE);var picking=WorldPickIndex.prepare(optimized.retainCpuScene(),WorldPickIndex.Limits.DEFAULT,device.memory().orElseThrow(),LIVE)) {
                    equal(beforeCpu,device.stats().cpuPayloadBytes(),"draw scratch releases after each complete helper unwind");var actual=read(optimized).image();pixels(read(exhaustive).image(),actual);var kept=optimized.info().stats();var all=exhaustive.info().stats();var compared=reference.info().stats();pixels(read(reference).image(),actual);equal(48,compared.faceBatchTests(),"independent linear reference tests each source group once");equal(compared.faceBatchTests(),compared.faceBoundsTests(),"linear work contains only leaves");check(kept.faceBatchTests()<48&&kept.faceBoundsTests()<=95,"hierarchy skips leaf tests under clipped parent groups");equal(compared.culledFaceBatches(),kept.culledFaceBatches(),"bulk culling covers exact reference groups");equal(compared.triangleIndices(),kept.triangleIndices(),"three-mode comparisons preserve original visible index submissions");equal(compared.translucentFaces(),kept.translucentFaces(),"hierarchy preserves exact sorted transparent face count");equal(compared.culledSolidFaces(),kept.culledSolidFaces(),"shared opaque and transparent mask agrees with linear reference");check(kept.culledFaceBatches()>0&&kept.culledTranslucentFaces()>0,"translucent groups outside clip are culled");equal(fixture.scene().usage().translucentFaces(),kept.translucentFaces()+kept.culledTranslucentFaces(),"surviving plus group-culled translucent faces cover the exact sorted source");check(kept.translucentFaces()<all.translucentFaces()&&kept.triangleIndices()<all.triangleIndices(),"group rejection reduces submitted faces and indices");equal(0,all.faceBatchTests(),"disabled range mode performs no group work");equal(0,all.faceBoundsTests(),"disabled mode performs no internal bounds work");equal(0,all.culledTranslucentFaces(),"disabled range mode preserves complete transparent order");if(mixed)check(kept.culledSolidFaces()>0&&kept.solidRangeTests()>0,"same cached groups serve opaque and translucent passes");
                    check(!picking.requiresPlaceholder(),"completed world frame admits full range hierarchy");var displayed=optimized.raster();var ray=new WorldRay(origin,16.5,4,28.5,0,-1,0,0,16);var options=optimized.info().pickOptions(WorldPickIndex.Options.DEFAULT);
                    var hierarchical=picking.query(ray,options,displayed,LIVE);var linear=picking.query(ray,options.withRangeHierarchy(false),displayed,LIVE);var allPrimitives=picking.query(ray,options.withBatchCulling(false),displayed,LIVE);check(!hierarchical.requiresPlaceholder()&&!linear.requiresPlaceholder()&&!allPrimitives.requiresPlaceholder(),"completed-frame traversal modes all finish");equal(allPrimitives.hit(),hierarchical.hit(),"completed displayed atlas and clip preserve exhaustive nearest source");equal(linear.hit(),hierarchical.hit(),"completed displayed source agrees with independent linear group traversal");check(hierarchical.hit().isPresent()&&hierarchical.stats().rangeNodes()>0,"known displayed top plane uses range hierarchy");var original=hierarchical.hit().orElseThrow();equal(fixture.scene().snapshot().state(fixture.scene().snapshot().extent().index(original.position())),original.state(),"hierarchical frame picking retains original native identity");
                    var preserved=optimized.info().pickOptions(WorldPickIndex.Options.DEFAULT.withRangeHierarchy(false).withRangeNodeTests(7));check(!preserved.rangeHierarchy()&&preserved.rangeNodeTests()==7&&preserved.clip().equals(clip),"frame clip handoff preserves hierarchy controls");var exhausted=picking.query(ray,options.withRangeNodeTests(0),displayed,LIVE);check(exhausted.requiresPlaceholder()&&exhausted.hit().isEmpty(),"displayed hierarchy budget failure publishes no partial pick");
                    glFinish();device.collect();var memory=device.memory().orElseThrow();long scratch=renderer.drawScratchBytes(scene,true),free=memory.stats().limitBytes()-memory.stats().chargedBytes();check(free>=scratch,"fixture has room before admission blocker");
                    try(var blocker=memory.hold("World draw scratch rejection",free-scratch+1)) {
                        var saved=device.stats();var host=host();failure(GlFailure.Code.LIMIT,()->renderer.renderFrame(scene,null,order,optimized.readbackTarget(),camera,clip,true));equal(saved,device.stats(),"rejected draw scratch adds no journal or payload");equal(host,host(),"scratch rejection preserves host bindings and state");check(optimized.readbackTarget().frameComplete(),"scratch rejection preserves previous complete target");
                    }
                    var unchanged=device.stats();var savedHost=host();throwsType(NullPointerException.class,()->GlFrame.render(renderer,4,scene,null,order,camera,32,32,clip,(GlCullingMode)null,LIVE),"null world strategy before frame admission");throwsType(NullPointerException.class,()->renderer.renderFrame(scene,null,order,optimized.readbackTarget(),camera,clip,(GlCullingMode)null),"null strategy before target write");throwsType(NullPointerException.class,()->renderer.drawScratchBytes(scene,(GlCullingMode)null),"null draw sizing strategy");equal(unchanged,device.stats(),"null strategy adds no journals");equal(savedHost,host(),"null strategy preserves host state");check(optimized.readbackTarget().frameComplete(),"null strategy preserves complete target");pixels(actual,read(optimized).image());
                }
            }
            var wideCamera=new GlCamera(origin,16,32,288,16,0.5,16,90,0.1,1024);var wideCpuOrder=WorldDrawOrder.prepare(fixture.scene(),wideCamera.drawView(),LIVE);
            try(var wideOrder=GlDrawPlan.create(scene,wideCpuOrder);var wide=GlFrame.render(renderer,5,scene,null,wideOrder,wideCamera,32,32,WorldClip.NONE,GlCullingMode.HIERARCHY,LIVE);var wideLinear=GlFrame.render(renderer,6,scene,null,wideOrder,wideCamera,32,32,WorldClip.NONE,GlCullingMode.LINEAR,LIVE)) {
                var stats=wide.info().stats();equal(1,stats.faceBoundsTests(),"contained source accepts complete face tree at root");equal(0,stats.faceBatchTests(),"root acceptance needs no leaf tests");equal(0,stats.culledFaceBatches(),"contained hierarchy keeps every original group");equal(wideLinear.info().stats().triangleIndices(),stats.triangleIndices(),"root acceptance preserves all original submitted triangles");pixels(read(wideLinear).image(),read(wide).image());
            }
            equal(GlWorldCulling.workingBytes(fixture.scene(),GlCullingMode.LINEAR),renderer.drawScratchBytes(scene,GlCullingMode.HIERARCHY),"hierarchy and linear modes admit identical scratch");
            var legacy=new GlRenderer.FrameStats(1,0,1,0,1,0,0,0,0,6,1,0,0,1,0,0);equal(0,legacy.faceBoundsTests(),"legacy world stats construction remains available");throwsType(IllegalArgumentException.class,()->new GlRenderer.FrameStats(1,0,1,0,1,0,0,0,0,6,1,0,0,2,0,0,1),"leaf tests cannot exceed nonzero total bound tests");
            glFinish();device.collect();
        }
    }
    private static void scalarDrawAdmission(GlDevice device) {
        var grid=new NoiseVolume.Grid(new MapSamples.Grid(-37,91,32,32,1),new MapSamples.Vertical(-64,1));var values=new double[1024];Arrays.fill(values,0.5);var present=new byte[1024];Arrays.fill(present,(byte)1);var volume=NoiseVolume.capture(context(),grid,values,present,NoiseVolume.Limits.DEFAULT,LIVE);var source=NoiseSurface.prepare(volume,new NoiseSurface.Style(0,1,new MapRaster.ScalarStyle(0,1,MapRamp.GRAYSCALE),NoiseSurface.Meshing.CELLS),NoiseSurface.Limits.DEFAULT,LIVE);check(!source.requiresPlaceholder(),"complete scalar differential fixture");var origin=new WorldSnapshot.Position(-37,-64,91);var camera=new GlCamera(origin,16,18,48,16,0.5,28,60,0.1,128);var clip=new WorldClip(new WorldSnapshot.Extent(-37,-64,115,32,1,8));
        try(var renderer=GlNoiseRenderer.create(device);var scene=upload(device,source)) {
            long beforeCpu=device.stats().cpuPayloadBytes();equal(1027L,renderer.drawScratchBytes(scene,true),"metadata-only scalar mask/math footprint");equal(1024L,renderer.drawScratchBytes(scene,false),"disabled scalar footprint omits mask");
            try(var optimized=GlNoiseFrame.render(renderer,1,scene,camera,32,32,clip,true,LIVE);var exhaustive=GlNoiseFrame.render(renderer,2,scene,camera,32,32,clip,false,LIVE);var linearFrame=GlNoiseFrame.render(renderer,3,scene,camera,32,32,clip,GlCullingMode.LINEAR,LIVE)) {
                equal(beforeCpu,device.stats().cpuPayloadBytes(),"scalar temporary CPU admission releases before frame adoption");var actual=read(optimized).image();pixels(read(exhaustive).image(),actual);var kept=optimized.info().stats();var all=exhaustive.info().stats();check(kept.culledBatches()>0&&kept.culledFaces()>0&&kept.submittedFaces()<all.submittedFaces(),"scalar group culling reduces submitted work");equal(source.size(),kept.submittedFaces()+kept.culledFaces(),"exact original scalar face coverage");equal(source.size(),all.submittedFaces(),"disabled mode submits the whole original surface");equal(0,all.culledFaces(),"disabled mode has no CPU group rejection");equal(volume.id(),optimized.info().volumeId(),"admission/differential mode preserves native volume identity");equal(optimized.info().style(),exhaustive.info().style(),"differential mode preserves actual display style");equal(clip,optimized.info().clip(),"exact display clip remains in completed image");
                pixels(actual,read(linearFrame).image());var linearStats=linearFrame.info().stats();equal(kept.submittedFaces(),linearStats.submittedFaces(),"hierarchy/linear GPU frames submit identical surviving faces");equal(kept.culledFaces(),linearStats.culledFaces(),"hierarchy/linear rejected totals match");equal(source.batchCount(),linearStats.boundsTests(),"linear GPU receipt records actual leaf tests");equal(source.batchCount(),linearStats.testedBatches(),"linear GPU receipt tests each leaf");check(kept.boundsTests()<=2*source.batchCount()-1&&kept.testedBatches()<=source.batchCount(),"hierarchical GPU receipt has bounded actual rejection work");equal(0,all.boundsTests(),"disabled GPU receipt has no bounds tests");equal(0,all.testedBatches(),"disabled GPU receipt has no rejection leaf tests");equal(renderer.drawScratchBytes(scene,GlCullingMode.LINEAR),renderer.drawScratchBytes(scene,GlCullingMode.HIERARCHY),"both rejection strategies share exact scratch admission");
                var outside=new WorldClip(new WorldSnapshot.Extent(2000,-64,2000,1,1,1));try(var root=GlNoiseFrame.render(renderer,4,scene,camera,32,32,outside,GlCullingMode.HIERARCHY,LIVE);var leaves=GlNoiseFrame.render(renderer,5,scene,camera,32,32,outside,GlCullingMode.LINEAR,LIVE)) { pixels(read(root).image(),read(leaves).image());equal(1,root.info().stats().boundsTests(),"disjoint GPU frame rejects whole source with one parent test");equal(0,root.info().stats().testedBatches(),"disjoint GPU frame needs no leaf bounds");equal(source.batchCount(),leaves.info().stats().boundsTests(),"linear disjoint frame retains independent leaf reference");equal(0,root.info().stats().submittedFaces(),"parent rejection submits no hidden instances"); }
                var whole=new GlCamera(origin,16,40,128,16,0.5,16,90,0.1,512);try(var accepted=GlNoiseFrame.render(renderer,6,scene,whole,32,32,WorldClip.NONE,GlCullingMode.HIERARCHY,LIVE);var leafFrame=GlNoiseFrame.render(renderer,7,scene,whole,32,32,WorldClip.NONE,GlCullingMode.LINEAR,LIVE)) { pixels(read(accepted).image(),read(leafFrame).image());equal(1,accepted.info().stats().boundsTests(),"contained GPU source accepts one whole parent");equal(0,accepted.info().stats().testedBatches(),"contained GPU source needs no leaf bounds");equal(source.size(),accepted.info().stats().submittedFaces(),"parent acceptance submits every original face");equal(source.batchCount(),leafFrame.info().stats().boundsTests(),"independent linear contained frame tests each leaf"); }
                var unchanged=device.stats();throwsType(NullPointerException.class,()->renderer.drawScratchBytes(scene,(GlCullingMode)null),"null scalar draw strategy rejected");throwsType(NullPointerException.class,()->GlNoiseFrame.render(renderer,6,scene,camera,32,32,clip,(GlCullingMode)null,LIVE),"null immutable-frame strategy rejected before admission");equal(unchanged,device.stats(),"null strategy creates no journal or target");equal(0,new GlNoiseRenderer.Stats(1,6,12).boundsTests(),"legacy submission receipt construction remains available");
                var pickOptions=optimized.info().pickOptions(new NoisePick.Options(source.size()));var pickRay=new WorldRay(origin,16.5,4,28.5,0,-1,0,0,16);var tree=NoisePick.query(source,pickRay,pickOptions,LIVE);var linear=NoisePick.query(source,pickRay,pickOptions.withRangeHierarchy(false),LIVE);var exhaustivePick=NoisePick.query(source,pickRay,pickOptions.withBatchCulling(false),LIVE);
                check(!tree.requiresPlaceholder()&&!linear.requiresPlaceholder()&&!exhaustivePick.requiresPlaceholder(),"completed scalar frame supports all traversal modes");equal(exhaustivePick.hit(),tree.hit(),"completed scalar hierarchy preserves exhaustive original sample");equal(linear.hit(),tree.hit(),"completed scalar hierarchy agrees with linear groups");check(tree.hit().isPresent()&&tree.rangeNodes()>0,"known clipped scalar top plane uses hierarchy");equal(0.5,tree.hit().orElseThrow().sample().value(),"completed picking keeps original scalar");
                var visited=optimized.info().pickOptions(new NoisePick.Options(source.size()).withVisitedFaceBudget(tree.visitedFaces()).withRangeNodeTests(NoisePick.MAX_RANGE_NODES));check(visited.faceBudget()==NoisePick.FaceBudget.VISITED&&visited.clip().equals(clip),"frame clip preserves visited-face policy and controls");equal(tree.hit(),NoisePick.query(source,pickRay,visited,LIVE).hit(),"bounded displayed query preserves exact source hit");var rejected=NoisePick.query(source,pickRay,visited.withVisitedFaceBudget(0),LIVE);check(rejected.requiresPlaceholder()&&rejected.hit().isEmpty()&&rejected.visitedFaces()==1,"displayed visited-budget failure keeps diagnostics without partial hit");
                glFinish();device.collect();var memory=device.memory().orElseThrow();long need=renderer.drawScratchBytes(scene,true),free=memory.stats().limitBytes()-memory.stats().chargedBytes();check(free>=need,"room before scalar admission blocker");
                try(var blocker=memory.hold("Scalar draw scratch rejection",free-need+1)) { var before=device.stats();var host=host();failure(GlFailure.Code.LIMIT,()->renderer.render(scene,optimized.readbackTarget(),camera,clip,true));equal(before,device.stats(),"scalar scratch rejection preserves known device payload/journals");equal(host,host(),"scalar scratch rejection preserves host draw/transfer state");check(optimized.readbackTarget().frameComplete(),"scalar scratch rejection preserves previous complete target"); }
                var distant=new GlCamera(new WorldSnapshot.Position(origin.x()+1_048_577,origin.y(),origin.z()),16,18,48,16,0.5,28,60,0.1,128);var before=device.stats();var host=host();failure(GlFailure.Code.LIMIT,()->renderer.render(scene,optimized.readbackTarget(),distant,clip,true));equal(before,device.stats(),"failed scalar preparation unwinds its admitted CPU scratch");equal(host,host(),"scalar precision rejection changes no host state");check(optimized.readbackTarget().frameComplete(),"scalar precision failure preserves previous target");pixels(actual,read(optimized).image());
            }
        }
    }
    private static void lifetimes(GlDevice device) {
        RenderInput<GlReadback.Pixels> retained=null;
        try {
            try(var renderer=GlMapRenderer.create(device);var image=upload(device,image());var frame=GlImageFrame.render(renderer,1,image,new MapView(2,2,0,0,1),2,2,LIVE);var job=GlReadback.begin(frame)) {
                until(job::ready,()->job.step(TINY,LIVE));retained=job.takeInput();frame.close();renderer.close();image.close();
            }
            try(var foreign=new GlDevice(new GlDevice.Limits(0,65536,65536,8,16,16,256))) { var before=foreign.stats();failure(GlFailure.Code.LIMIT,()->GlTarget.create(foreign,1,1));equal(before,foreign.stats(),"GPU budget rejection preserves empty device"); }
            device.close();equal(16,retained.value().image().bytes(),"taken readback owns independent CPU bytes beyond device closure");var duplicate=retained.retain();retained.close();retained=duplicate;equal(COLORS[3],retained.value().image().rgba(1,1),"independent retained pixels survive wrapper release");
        }finally { if(retained!=null)retained.close(); }
    }
    private static void report(Path path,Instant started) throws IOException {
        check(passed.size()==18,"complete expected GPU group count");var values=new Properties();values.setProperty("status","passed");values.setProperty("scope","synthetic OpenGL contracts; no native parity or preview readiness");values.setProperty("groups",Integer.toString(passed.size()));values.setProperty("started",started.toString());values.setProperty("completed",Instant.now().toString());values.setProperty("java",System.getProperty("java.runtime.version"));values.setProperty("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));values.setProperty("lwjgl",Version.getVersion());
        values.setProperty("gl.vendor",driver.vendor());values.setProperty("gl.renderer",driver.renderer());values.setProperty("gl.version",driver.version());values.setProperty("gl.shadingLanguage",driver.shadingLanguage());values.setProperty("gl.major",Integer.toString(driver.major()));values.setProperty("gl.minor",Integer.toString(driver.minor()));values.setProperty("gl.profileMask",Integer.toString(driver.profileMask()));values.setProperty("gl.contextFlags",Integer.toString(driver.contextFlags()));values.setProperty("gl.maximumTexture",Integer.toString(driver.maximumTexture()));values.setProperty("gl.maximumRenderbuffer",Integer.toString(driver.maximumRenderbuffer()));values.setProperty("gl.maximumVertexAttributes",Integer.toString(driver.maximumVertexAttributes()));values.setProperty("gl.maximumTextureUnits",Integer.toString(driver.maximumTextureUnits()));values.setProperty("gl.optionalFeatures",String.join(",",driver.optionalFeatures()));for(int i=0;i<passed.size();i++)values.setProperty("group."+(i+1),passed.get(i));
        Files.createDirectories(path.getParent());var temporary=Files.createTempFile(path.getParent(),"gpu-contracts-",".part");try { try(var output=Files.newOutputStream(temporary)) { values.store(output,"Actual completed GPU contract run"); }Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }finally { Files.deleteIfExists(temporary); }
    }
    public static void main(String[] arguments) throws IOException {
        if(arguments.length!=1)throw new IllegalArgumentException("Supply one output report path for explicit GPU stage validation");var output=Path.of(arguments[0]).toAbsolutePath().normalize();Files.deleteIfExists(output);var started=Instant.now();
        check(System.getProperty("os.name").startsWith("Windows")&&Set.of("amd64","x86_64").contains(System.getProperty("os.arch")),"GPU stage validation requires supported Windows x64");check(Runtime.version().feature()==25,"GPU stage validation requires Java 25");
        var glfwFailures=new ArrayList<String>();
        try(var errors=GLFWErrorCallback.create((code,description)-> { var reason=GLFWErrorCallback.getDescription(description);if(reason.length()>4096)reason=reason.substring(0,4096);if(glfwFailures.size()<16)glfwFailures.add(code+": "+reason);System.err.println("GLFW "+code+": "+reason); })) {
            glfwSetErrorCallback(errors);long window=0;boolean initialized=false;
            try {
                check(glfwInit(),"GLFW initialization");initialized=true;glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);window=glfwCreateWindow(64,64,"Iris GPU contracts",0,0);check(window!=0,"hidden OpenGL 3.3 core context creation");glfwMakeContextCurrent(window);GL.createCapabilities();
                group("context provenance and boundary guards",GlGpuContracts::deviceBoundaries);group("all renderer shader programs",GlGpuContracts::shaders);group("original image upload and exact RGBA readback",GlGpuContracts::imagePixels);group("map upload and exact original sample receipt",GlGpuContracts::mapPixels);group("immutable image navigation",GlGpuContracts::navigation);group("upload cancellation and queue recovery",GlGpuContracts::uploadRecovery);group("readback preflight and owned transfer",GlGpuContracts::readbackRecovery);group("world map image replacement admission",GlGpuContracts::transferAdmission);group("readback and atlas replacement admission",GlGpuContracts::outputAdmission);group("host state isolation",GlGpuContracts::hostState);group("scalar drawing clipping and identity",GlGpuContracts::scalar);group("scalar GPU geometry reuse and color upload",GlGpuContracts::scalarRecolor);group("world textured geometry and original-state picking",GlGpuContracts::worldGeometry);group("world cutout and ordered transparency",GlGpuContracts::worldAlpha);group("world animated atlas immutability",GlGpuContracts::worldAnimation);group("sparse atlas clock observation",GlGpuContracts::atlasClockIndex);group("bounded live atlas playback",GlGpuContracts::liveAtlasPlayback);group("connected live playback and recovery",GlGpuContracts::liveWorldPlayback);group("initial world animation clock adoption",GlGpuContracts::worldClockPriming);group("world tint lighting and host state",GlGpuContracts::worldMaterials);group("world material geometry and atlas copies",GlGpuContracts::worldMaterialCopies);group("world material copy recovery and connected view",GlGpuContracts::worldMaterialRecovery);group("exact transparent order rebinding",GlGpuContracts::drawPlanRebinding);group("transparent material order reuse",GlGpuContracts::materialOrderReuse);group("transparent order admission recovery",GlGpuContracts::materialOrderRecovery);group("owned world lighting queue and view handoffs",GlGpuContracts::worldOwnedMaterials);group("displayed animated atlas material copying",GlGpuContracts::displayedMaterialAtlas);group("displayed atlas copy admission and recovery",GlGpuContracts::displayedAtlasRecovery);group("world empty diagnostic and completed-frame recovery",GlGpuContracts::worldDiagnostics);group("world shared translucent range culling and admission",GlGpuContracts::worldRangeCulling);group("scalar draw admission and differential clipping",GlGpuContracts::scalarDrawAdmission);group("budgets and detached readback lifetime",GlGpuContracts::lifetimes);
            }finally { GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);if(initialized)glfwTerminate();glfwSetErrorCallback(null); }
        }
        check(glfwFailures.isEmpty(),"GLFW errors prevent successful validation evidence: "+glfwFailures);report(output,started);System.out.println("PASS "+passed.size()+" synthetic GPU contract groups; no native parity established");
    }
}
