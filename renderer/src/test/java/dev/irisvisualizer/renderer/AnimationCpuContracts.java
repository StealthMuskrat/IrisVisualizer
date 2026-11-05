package dev.irisvisualizer.renderer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Synthetic animation/atlas contracts; compile during implementation, execute only at stage validation. */
public final class AnimationCpuContracts {
    private static int groups;
    private static final AssetId BLOCK=new AssetId("fixture","animated_block"),MODEL=new AssetId("fixture","block/animated_block"),ANIMATED=new AssetId("fixture","block/animated"),STATIC=new AssetId("fixture","block/static");
    private AnimationCpuContracts() {}
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void equal(Object expected,Object actual,String message) { check(Objects.equals(expected,actual),message+": expected "+expected+", got "+actual); }
    private static void near(double expected,double actual,String message) { check(Double.isFinite(actual)&&Math.abs(expected-actual)<1e-12,message); }
    private static <T extends Throwable> void throwsType(Class<T> type,Runnable action,String message) {
        try { action.run(); }catch(Throwable failure) { if(type.isInstance(failure))return;throw new AssertionError(message+": wrong exception",failure); }
        throw new AssertionError(message+": exception missing");
    }
    private static void group(String name,Runnable action) { action.run();groups++;System.out.println("PASS "+name); }
    private static RgbaImage sheet() { return RgbaImage.owned(1,2,new byte[]{(byte)255,0,0,(byte)128,0,0,(byte)255,(byte)255}); }
    private static TextureSprite sprite(String metadata) {
        var image=sheet();var parsed=TextureMetadata.read(ANIMATED,metadata==null?null:metadata.getBytes(StandardCharsets.UTF_8),image,4096,128,()->false);return new TextureSprite(ANIMATED,"0".repeat(64),Optional.empty(),image,parsed);
    }
    private static void metadata() {
        var plain=sprite(null);check(!plain.animated()&&!plain.interpolate(),"no metadata means whole static sheet");equal(1,plain.frameWidth(),"static source width");equal(2,plain.frameHeight(),"static source height");
        var animated=sprite("{\"animation\":{\"frametime\":2,\"interpolate\":true}}");check(animated.animated()&&animated.interpolate(),"effective animation");equal(List.of(new TextureSprite.Frame(0,2),new TextureSprite.Frame(1,2)),animated.frames(),"implicit square-tile schedule");equal(4L,animated.cycleTicks(),"cycle duration");
        var settings=sprite("{\"texture\":{\"blur\":true,\"clamp\":true,\"mipmap_strategy\":\"dark_cutout\",\"alpha_cutoff_bias\":0.25}}").settings();check(settings.blur()&&settings.clamp(),"texture settings retained");equal(TextureSprite.MipmapStrategy.DARK_CUTOUT,settings.mipmapStrategy(),"mipmap policy metadata");equal(0.25f,settings.alphaCutoffBias(),"cutoff bias metadata");
    }
    private static void loneFrames() {
        for(String frames:List.of("[]","[1]","[{\"index\":1,\"time\":7}]")) {
            var value=sprite("{\"animation\":{\"interpolate\":true,\"frames\":"+frames+"}}");check(!value.animated()&&!value.interpolate(),"zero/one schedule entries stay static");equal(List.of(new TextureSprite.Frame(0,1)),value.frames(),"static upload uses top-left tile");equal(new TextureSprite.PixelSample(0,0,0),value.pixelSample(Long.MAX_VALUE,0.9),"lone nonzero schedule entry cannot select another tile");
        }
    }
    private static void schedule() {
        var value=sprite("{\"animation\":{\"interpolate\":true,\"frames\":[{\"index\":1,\"time\":3},{\"index\":0,\"time\":2},{\"index\":1,\"time\":1}]}}");
        int[] current={1,1,1,0,0,1},next={0,0,0,1,1,1},steps={0,333,666,0,500,0};
        for(int tick=0;tick<6;tick++)equal(new TextureSprite.PixelSample(current[tick],next[tick],steps[tick]),value.pixelSample(tick,0),"explicit repeated schedule tick "+tick);
        equal(value.pixelSample(1,0),value.pixelSample(Long.MAX_VALUE,0.75),"large integer clocks use exact cycle modulo");equal(List.of(0,1),value.referencedFrames(),"distinct referenced tile list");
        near(0.5,value.sample(1,0.5).blend(),"continuous scheduling metadata");equal(value.pixelSample(1,0),value.pixelSample(1,0.99),"partial ticks do not advance quantized pixels");
        throwsType(UnsupportedOperationException.class,()->value.frames().clear(),"immutable schedule");
    }
    private static void metadataGuards() {
        for(String input:List.of("{\"animation\":{\"frametime\":0}}","{\"animation\":{\"frames\":[2]}}","{\"animation\":{\"frames\":[0.5]}}","{\"animation\":{\"width\":2}}","{\"animation\":{\"unsupported\":true}}"))throwsType(ModelFields.Failure.class,()->sprite(input),"invalid metadata rejected");
        var value=sprite("{\"animation\":{}}");throwsType(IllegalArgumentException.class,()->value.pixelSample(-1,0),"negative clock");throwsType(IllegalArgumentException.class,()->value.sample(0,1),"partial tick exclusive upper bound");throwsType(IllegalArgumentException.class,()->value.sample(0,Double.NaN),"nonfinite partial tick");
    }
    private static void canonicalSamples() {
        equal(new TextureSprite.PixelSample(1,1,0),new TextureSprite.PixelSample(0,1,1000),"full blend selects next tile");equal(new TextureSprite.PixelSample(0,0,0),new TextureSprite.PixelSample(0,1,0),"zero blend canonicalized");equal(new TextureSprite.PixelSample(1,1,0),new TextureSprite.PixelSample(1,1,500),"repeated tiles have no pixel blend");throwsType(IllegalArgumentException.class,()->new TextureSprite.PixelSample(0,1,1001),"blend-step hard bound");
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }catch(NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static void chunk(DataOutputStream out,String name,byte[] bytes) throws IOException {
        var type=name.getBytes(StandardCharsets.US_ASCII);var crc=new CRC32();crc.update(type);crc.update(bytes);out.writeInt(bytes.length);out.write(type);out.write(bytes);out.writeInt((int)crc.getValue());
    }
    private static byte[] png(int... colors) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeLong(0x89504e470d0a1a0aL);var headers=new ByteArrayOutputStream();var header=new DataOutputStream(headers);header.writeInt(1);header.writeInt(colors.length);header.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",headers.toByteArray());
            var packed=new ByteArrayOutputStream();try(var zlib=new DeflaterOutputStream(packed)) { for(int rgba:colors)zlib.write(new byte[]{0,(byte)(rgba>>>24),(byte)(rgba>>>16),(byte)(rgba>>>8),(byte)rgba}); }chunk(out,"IDAT",packed.toByteArray());chunk(out,"IEND",new byte[0]);return bytes.toByteArray();
        }catch(IOException impossible) { throw new AssertionError(impossible); }
    }
    private static void add(Map<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset> map,ClientAssetSnapshot.Kind kind,AssetId id,byte[] bytes) { map.put(new ClientAssetSnapshot.Key(kind,id),ClientAssetSnapshot.Asset.owned("synthetic-animation/"+kind+"/"+id,bytes,digest(bytes))); }
    private static AtlasLayout layout(long workingBytes,long updateBytes) {
        var entries=new TreeMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset>();add(entries,ClientAssetSnapshot.Kind.BLOCKSTATE,BLOCK,"{\"variants\":{\"\":{\"model\":\"fixture:block/animated_block\"}}}".getBytes(StandardCharsets.UTF_8));
        add(entries,ClientAssetSnapshot.Kind.MODEL,MODEL,"{\"textures\":{\"a\":\"fixture:block/animated\",\"b\":\"fixture:block/static\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"south\":{\"texture\":\"#a\"},\"east\":{\"texture\":\"#b\"}}}]}".getBytes(StandardCharsets.UTF_8));
        add(entries,ClientAssetSnapshot.Kind.TEXTURE,ANIMATED,png(0xff000080,0x0000ffff));add(entries,ClientAssetSnapshot.Kind.TEXTURE,STATIC,png(0x102030ff));add(entries,ClientAssetSnapshot.Kind.TEXTURE_METADATA,ANIMATED,"{\"animation\":{\"frametime\":2,\"interpolate\":true}}".getBytes(StandardCharsets.UTF_8));
        long bytes=entries.values().stream().mapToLong(ClientAssetSnapshot.Asset::bytes).sum();var assets=new ClientAssetSnapshot(new ClientAssetSnapshot.Receipt(Path.of("synthetic-animation-fixture"),"0".repeat(64),bytes,BlockModels.PROFILE,BlockModels.PROFILE,entries.size(),0,bytes),entries);var state=new RenderBlockState(991,"fixture:animated_block",BLOCK,Map.of(),Map.of());
        var block=GeometryBaker.bake(BlockModels.resolve(assets,state,()->false),new int[]{0},()->false);check(!block.requiresPlaceholder(),"animated fixture block: "+block.problems());var preparation=TextureMaterials.prepare(assets,block,()->false);check(!preparation.requiresPlaceholder(),"animated fixture textures: "+preparation.problems());
        var limits=new AtlasBuilder.Limits(1,2,2,8,4,2,1,64,1024,128,1024,updateBytes,workingBytes,1024);var layout=AtlasBuilder.plan(List.of(preparation),limits,()->false);check(!layout.requiresPlaceholder(),"animated layout: "+layout.problems());equal(2,layout.pages().size(),"small page limit separates sprites");return layout;
    }
    private static AtlasRaster baseline(AtlasLayout layout) { var raster=AtlasImages.capture(layout,0,0,()->false);check(!raster.requiresPlaceholder(),"complete baseline");return raster; }
    private static int content(AtlasRaster raster,AssetId id) { var slot=raster.layout().sprites().get(id);return raster.pages().get(slot.page()).rgba(slot.x(),slot.y()); }
    private static void patchPixels() {
        var source=baseline(layout(4096,512));var update=AtlasImages.updates(source,1,0.9,()->false);check(!update.requiresPlaceholder(),"complete half-blend patch");equal(1,update.patches().size(),"one animated slot changed");equal(36L,update.bytes(),"complete three-by-three RGBA gutter patch");var patch=update.patches().getFirst();
        for(int y=0;y<3;y++)for(int x=0;x<3;x++)equal(0x800080c0,patch.pixels().rgba(x,y),"independent half-blend straight RGBA including alpha");
        equal(0xff000080,content(source,ANIMATED),"old source pixels unchanged");equal(new TextureSprite.PixelSample(0,1,500),update.samples().get(ANIMATED),"actual blend receipt");
    }
    private static void noOpClocks() {
        var source=baseline(layout(4096,512));var a=AtlasFrameWorker.Request.capture(source,0,0.1);var b=AtlasFrameWorker.Request.capture(source,0,0.9);check(a.samePixels(b),"fractional clocks coalesce by pixel inputs");equal(0,a.changedSprites(),"no implicit fractional animation");
        var update=AtlasImages.updates(source,4,0,()->false);equal(0,update.patches().size(),"cycle wrap needs no pixel patch");var applied=AtlasImages.apply(source,update,()->false);for(int page=0;page<source.pages().size();page++)check(source.pages().get(page)==applied.pages().get(page),"unchanged page sharing");
        check(!a.samePixels(AtlasFrameWorker.Request.capture(baseline(source.layout()),0,0.1)),"coalescing still requires exact raster baseline");
    }
    private static void pageSharing() {
        var source=baseline(layout(4096,512));var request=AtlasFrameWorker.Request.capture(source,1,0);var update=AtlasImages.updates(source,1,0,()->false);var plan=AtlasFramePlan.prepare(source,update,()->false);check(!plan.requiresPlaceholder(),"complete plan");var result=plan.raster().orElseThrow();int animated=source.layout().sprites().get(ANIMATED).page(),fixed=source.layout().sprites().get(STATIC).page();
        equal(List.of(animated),plan.changedPages(),"only animated page copied");equal(1,plan.patches(animated).size(),"patch grouping");equal(0,plan.patches(fixed).size(),"static page has no patch");check(source.pages().get(animated)!=result.pages().get(animated),"changed page fresh");check(source.pages().get(fixed)==result.pages().get(fixed),"unchanged page shared");
        equal(64L,plan.copiedBytes(),"one complete page copy");equal(100L,plan.extraCpuBytes(),"old/new page plus patch peak");equal(plan.extraCpuBytes(),request.extraCpuBytes(),"worker metadata preflight matches actual plan");equal(source.layout().usage().orElseThrow().knownPayloadBytes()+100,request.reservation(),"complete source and extras reservation");equal(0x800080c0,content(result,ANIMATED),"new pixels");equal(0x102030ff,content(result,STATIC),"static pixels preserved");equal(0xff000080,content(source,ANIMATED),"old raster immutable");
    }
    private static void staleBaseline() {
        var layout=layout(4096,512);var a=baseline(layout);var b=baseline(layout);var update=AtlasImages.updates(a,1,0,()->false);var failed=AtlasImages.apply(b,update,()->false);check(failed.requiresPlaceholder()&&failed.pages().isEmpty()&&failed.samples().isEmpty(),"stale update exposes no partial pages");equal(ModelPlan.Code.STALE_ATLAS,failed.problems().getFirst().code(),"stale diagnostic");var plan=AtlasFramePlan.prepare(b,update,()->false);check(plan.requiresPlaceholder()&&plan.raster().isEmpty()&&plan.changedPages().isEmpty(),"stale plan atomic");equal(0xff000080,content(b,ANIMATED),"rejected baseline unchanged");
    }
    private static void limitsAndCancellation() {
        var base=baseline(layout(4096,512));long peak=base.layout().usage().orElseThrow().knownPayloadBytes()+100;var exact=baseline(layout(peak,512));check(!AtlasFramePlan.prepare(exact,AtlasImages.updates(exact,1,0,()->false),()->false).requiresPlaceholder(),"exact source/patch/copy peak admitted");
        var lower=baseline(layout(peak-1,512));var update=AtlasImages.updates(lower,1,0,()->false);check(!update.requiresPlaceholder(),"patch-only demand fits lower limit");var failed=AtlasFramePlan.prepare(lower,update,()->false);check(failed.requiresPlaceholder()&&failed.raster().isEmpty()&&failed.copiedBytes()==0,"copy-on-write peak fails without output");throwsType(IllegalArgumentException.class,()->AtlasFrameWorker.Request.capture(lower,1,0),"worker rejects lower peak before scheduling");
        var small=baseline(layout(4096,35));var rejected=AtlasImages.updates(small,1,0,()->false);check(rejected.requiresPlaceholder()&&rejected.patches().isEmpty()&&rejected.bytes()==0,"patch budget failure atomic");
        throwsType(CancellationException.class,()->AtlasImages.capture(base.layout(),0,0,()->true),"raster cancellation");throwsType(CancellationException.class,()->AtlasImages.updates(base,1,0,()->true),"patch cancellation");throwsType(CancellationException.class,()->AtlasFramePlan.prepare(base,AtlasImages.updates(base,1,0,()->false),()->true),"plan cancellation");equal(0xff000080,content(base,ANIMATED),"cancelled preparation leaves baseline unchanged");
    }
    /** Deferred deterministic worker scheduling; no thread or native/GL context is created. */
    private static final class QueuedExecutor extends AbstractExecutorService {
        private final List<Runnable> queued=new ArrayList<>();private boolean shutdown;
        @Override public void execute(Runnable command) { if(shutdown)throw new RejectedExecutionException();queued.add(Objects.requireNonNull(command)); }
        @Override public void shutdown() { shutdown=true; }
        @Override public List<Runnable> shutdownNow() { shutdown=true;var result=List.copyOf(queued);queued.clear();return result; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown&&queued.isEmpty(); }
        @Override public boolean awaitTermination(long timeout,TimeUnit unit) { return isTerminated(); }
        void runAll() { while(!queued.isEmpty())queued.removeFirst().run(); }
    }
    private static void destinationPreflight() {
        var source=baseline(layout(4096,512));var request=AtlasFrameWorker.Request.capture(source,1,0);
        try(var memory=new RenderMemory(new RenderMemory.Limits(8192,4));var foreign=new RenderMemory(new RenderMemory.Limits(8192,4));var executor=new QueuedExecutor();var worker=new AtlasFrameWorker(8192,memory,()->executor)) {
            var idle=worker.status();worker.validateDestination(memory);check(worker.status()==idle,"valid preflight leaves idle status unchanged");equal(0,memory.stats().accounts(),"preflight creates no admission");
            long token=worker.submit(request);equal(1L,token,"preflight consumes no worker token");var queued=worker.status();var charged=memory.stats();long reserved=worker.reservedBytes();
            for(RenderMemory destination:new RenderMemory[]{null,foreign}) {
                throwsType(IllegalArgumentException.class,()->worker.validateDestination(destination),"tracked destination mismatch");
                check(worker.status()==queued,"rejection preserves queued status identity");equal(charged,memory.stats(),"rejection preserves complete charge/account snapshot");equal(reserved,worker.reservedBytes(),"rejection preserves queued reservation");equal(1,executor.queued.size(),"rejection leaves queued execution intact");
            }
            worker.validateDestination(memory);executor.runAll();var ready=worker.status();equal(AtlasFrameWorker.Phase.READY,ready.phase(),"queued plan remains executable");var readyCharge=memory.stats();
            throwsType(IllegalArgumentException.class,()->worker.validateDestination(foreign),"READY destination mismatch");check(worker.status()==ready,"rejection preserves READY status identity");equal(readyCharge,memory.stats(),"READY admission unchanged");
            try(var completion=worker.take(token).orElseThrow();var input=completion.retainInput()) {
                check(completion.plan().expected()==source,"exact source baseline survives preflight");equal(0L,worker.reservedBytes(),"transferred output is external");long bytes=memory.stats().chargedBytes();check(bytes>0,"taken plan remains charged");
                worker.validateDestination(memory);worker.close();throwsType(IllegalStateException.class,()->worker.validateDestination(memory),"closed worker rejected before binding");equal(bytes,memory.stats().chargedBytes(),"validation and worker close preserve transferred input");check(input.value()==completion.plan(),"independent plan remains usable");
            }
            equal(0L,memory.stats().chargedBytes(),"last transferred owner releases charge");equal(0,memory.stats().accounts(),"no leaked preflight/worker account");equal(0,foreign.stats().accounts(),"foreign destination untouched");
        }
        try(var memory=new RenderMemory();var executor=new QueuedExecutor();var worker=new AtlasFrameWorker(8192,null,()->executor)) {
            var idle=worker.status();worker.validateDestination(null);worker.validateDestination(memory);check(worker.status()==idle,"untracked external-admission compatibility");equal(0,memory.stats().accounts(),"untracked preflight creates no destination account");
            try { var counter=AtlasFrameWorker.class.getDeclaredField("revision");counter.setAccessible(true);counter.setLong(worker,Long.MAX_VALUE); }
            catch(ReflectiveOperationException failure) { throw new AssertionError("controlled exhausted-worker fixture",failure); }
            throwsType(IllegalStateException.class,()->worker.validateDestination(null),"exhausted worker rejected before binding");check(worker.status()==idle,"exhaustion rejection publishes no status");equal(0,executor.queued.size(),"preflight schedules no work");
        }
    }
    private static void retainedCompletion() {
        destinationPreflight();
        var source=baseline(layout(4096,512));var plan=AtlasFramePlan.prepare(source,AtlasImages.updates(source,1,0,()->false),()->false);long bytes=source.layout().usage().orElseThrow().knownPayloadBytes()+plan.extraCpuBytes();
        try(var memory=new RenderMemory(new RenderMemory.Limits(8192,4))) { var completion=new AtlasFrameWorker.Completion(1,plan,memory.hold("prepared atlas",bytes));var input=completion.retainInput();var independent=input.retain();completion.close();input.close();check(independent.value()==plan,"independent exact CPU plan");equal(bytes,memory.stats().chargedBytes(),"retained plan remains fully charged");memory.close();equal(bytes,memory.stats().chargedBytes(),"coordinator close preserves plan charge");independent.close();equal(0L,memory.stats().chargedBytes(),"last plan input releases charge");equal(0,memory.stats().accounts(),"no leaked plan account"); }
    }
    public static void main(String[] arguments) {
        if(arguments.length!=0)throw new IllegalArgumentException("No arguments supported");
        group("animation and texture metadata",AnimationCpuContracts::metadata);
        group("zero and lone-frame static behavior",AnimationCpuContracts::loneFrames);
        group("integer repeated-frame scheduling",AnimationCpuContracts::schedule);
        group("animation metadata and clock guards",AnimationCpuContracts::metadataGuards);
        group("canonical pixel sample inputs",AnimationCpuContracts::canonicalSamples);
        group("independent RGBA interpolation patches",AnimationCpuContracts::patchPixels);
        group("same-pixel clock coalescing",AnimationCpuContracts::noOpClocks);
        group("copy-on-write pages and accounting",AnimationCpuContracts::pageSharing);
        group("exact stale baseline rejection",AnimationCpuContracts::staleBaseline);
        group("atlas working limits and cancellation",AnimationCpuContracts::limitsAndCancellation);
        group("independent atlas completion inputs",AnimationCpuContracts::retainedCompletion);
        System.out.println("PASS "+groups+" synthetic animation groups; no native/GPU parity is claimed.");
    }
}
