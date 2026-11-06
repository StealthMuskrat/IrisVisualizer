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
        check(passed.size()==11,"complete expected GPU group count");var values=new Properties();values.setProperty("status","passed");values.setProperty("scope","synthetic OpenGL contracts; no native parity or preview readiness");values.setProperty("groups",Integer.toString(passed.size()));values.setProperty("started",started.toString());values.setProperty("completed",Instant.now().toString());values.setProperty("java",System.getProperty("java.runtime.version"));values.setProperty("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));values.setProperty("lwjgl",Version.getVersion());
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
                group("context provenance and boundary guards",GlGpuContracts::deviceBoundaries);group("all renderer shader programs",GlGpuContracts::shaders);group("original image upload and exact RGBA readback",GlGpuContracts::imagePixels);group("map upload and exact original sample receipt",GlGpuContracts::mapPixels);group("immutable image navigation",GlGpuContracts::navigation);group("upload cancellation and queue recovery",GlGpuContracts::uploadRecovery);group("readback preflight and owned transfer",GlGpuContracts::readbackRecovery);group("host state isolation",GlGpuContracts::hostState);group("scalar drawing clipping and identity",GlGpuContracts::scalar);group("scalar GPU geometry reuse and color upload",GlGpuContracts::scalarRecolor);group("budgets and detached readback lifetime",GlGpuContracts::lifetimes);
            }finally { GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);if(initialized)glfwTerminate();glfwSetErrorCallback(null); }
        }
        check(glfwFailures.isEmpty(),"GLFW errors prevent successful validation evidence: "+glfwFailures);report(output,started);System.out.println("PASS "+passed.size()+" synthetic GPU contract groups; no native parity established");
    }
}
