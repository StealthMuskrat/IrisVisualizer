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
    private static byte[] png(int... colors) { return pngImage(1,colors.length,colors); }
    private static byte[] pngImage(int width,int height,int[] colors) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeLong(0x89504e470d0a1a0aL);var headers=new ByteArrayOutputStream();var header=new DataOutputStream(headers);header.writeInt(width);header.writeInt(height);header.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",headers.toByteArray());
            var packed=new ByteArrayOutputStream();try(var zlib=new DeflaterOutputStream(packed)) { for(int y=0;y<height;y++) { zlib.write(0);for(int x=0;x<width;x++) { int rgba=colors[y*width+x];zlib.write(new byte[]{(byte)(rgba>>>24),(byte)(rgba>>>16),(byte)(rgba>>>8),(byte)rgba}); } } }chunk(out,"IDAT",packed.toByteArray());chunk(out,"IEND",new byte[0]);return bytes.toByteArray();
        }catch(IOException impossible) { throw new AssertionError(impossible); }
    }
    private static void add(Map<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset> map,ClientAssetSnapshot.Kind kind,AssetId id,byte[] bytes) { map.put(new ClientAssetSnapshot.Key(kind,id),ClientAssetSnapshot.Asset.owned("synthetic-animation/"+kind+"/"+id,bytes,digest(bytes))); }
    private static AtlasLayout layout(long workingBytes,long updateBytes) { return layout(workingBytes,updateBytes,"{\"animation\":{\"frametime\":2,\"interpolate\":true}}"); }
    private static AtlasLayout layout(long workingBytes,long updateBytes,String metadata) { return layout(workingBytes,updateBytes,metadata,null); }
    private static AtlasLayout layout(long workingBytes,long updateBytes,String metadata,String secondary) {
        return layout(workingBytes,updateBytes,metadata,secondary,1,new int[]{0xff000080,0x0000ffff});
    }
    private static AtlasLayout patterned(long workingBytes,long updateBytes,boolean interpolate,int... indices) {
        int[] colors=new int[32];Arrays.fill(colors,0x203040ff);for(int i:indices)colors[16+i]=0x90a0b0ff;
        return layout(workingBytes,updateBytes,"{\"animation\":{\"frametime\":2,\"interpolate\":"+interpolate+"}}",null,4,colors);
    }
    private static AtlasLayout layout(long workingBytes,long updateBytes,String metadata,String secondary,int width,int[] colors) {
        var entries=new TreeMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset>();add(entries,ClientAssetSnapshot.Kind.BLOCKSTATE,BLOCK,"{\"variants\":{\"\":{\"model\":\"fixture:block/animated_block\"}}}".getBytes(StandardCharsets.UTF_8));
        add(entries,ClientAssetSnapshot.Kind.MODEL,MODEL,"{\"textures\":{\"a\":\"fixture:block/animated\",\"b\":\"fixture:block/static\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"south\":{\"texture\":\"#a\"},\"east\":{\"texture\":\"#b\"}}}]}".getBytes(StandardCharsets.UTF_8));
        add(entries,ClientAssetSnapshot.Kind.TEXTURE,ANIMATED,pngImage(width,colors.length/width,colors));add(entries,ClientAssetSnapshot.Kind.TEXTURE,STATIC,secondary==null?png(0x102030ff):png(0x102030ff,0x405060ff));if(secondary!=null)add(entries,ClientAssetSnapshot.Kind.TEXTURE_METADATA,STATIC,secondary.getBytes(StandardCharsets.UTF_8));if(metadata!=null)add(entries,ClientAssetSnapshot.Kind.TEXTURE_METADATA,ANIMATED,metadata.getBytes(StandardCharsets.UTF_8));
        long bytes=entries.values().stream().mapToLong(ClientAssetSnapshot.Asset::bytes).sum();var assets=new ClientAssetSnapshot(new ClientAssetSnapshot.Receipt(Path.of("synthetic-animation-fixture"),"0".repeat(64),bytes,BlockModels.PROFILE,BlockModels.PROFILE,entries.size(),0,bytes),entries);var state=new RenderBlockState(991,"fixture:animated_block",BLOCK,Map.of(),Map.of());
        var block=GeometryBaker.bake(BlockModels.resolve(assets,state,()->false),new int[]{0},()->false);check(!block.requiresPlaceholder(),"animated fixture block: "+block.problems());var preparation=TextureMaterials.prepare(assets,block,()->false);check(!preparation.requiresPlaceholder(),"animated fixture textures: "+preparation.problems());
        var limits=new AtlasBuilder.Limits(1,2,2,8,width==1?4:8,2,1,4096,1024,1024,1024,updateBytes,workingBytes,1024);var layout=AtlasBuilder.plan(List.of(preparation),limits,()->false);check(!layout.requiresPlaceholder(),"animated layout: "+layout.problems());equal(2,layout.pages().size(),"small page limit separates sprites");return layout;
    }
    private static AtlasRaster baseline(AtlasLayout layout) { var raster=AtlasImages.capture(layout,0,0,()->false);check(!raster.requiresPlaceholder(),"complete baseline");return raster; }
    private static int content(AtlasRaster raster,AssetId id) { var slot=raster.layout().sprites().get(id);return raster.pages().get(slot.page()).rgba(slot.x(),slot.y()); }
    /** Independent linear schedule oracle, deliberately separate from binary slot/window helpers. */
    private static TextureSprite.PixelSample clockOracle(TextureSprite sprite,long tick) {
        long time=tick%sprite.cycleTicks();int slot=0;while(time>=sprite.frames().get(slot).ticks())time-=sprite.frames().get(slot++).ticks();
        int current=sprite.frames().get(slot).index(),next=sprite.frames().get((slot+1)%sprite.frames().size()).index();int blend=sprite.interpolate()?(int)(((float)time/sprite.frames().get(slot).ticks())*1000.0f):0;
        if(blend>=1000)return new TextureSprite.PixelSample(next,next,0);if(blend==0||current==next)return new TextureSprite.PixelSample(current,current,0);return new TextureSprite.PixelSample(current,next,blend);
    }
    private static void clockWindows() {
        var stepped=sprite("{\"animation\":{\"frames\":[{\"index\":1,\"time\":3},{\"index\":0,\"time\":2},{\"index\":1,\"time\":1}]}}");
        equal(2L,stepped.pixelWindow(0,0.9).lastTick(),"stepped interval reaches current entry end");equal(4L,stepped.pixelWindow(3,0).lastTick(),"unequal stepped duration boundary");equal(5L,stepped.pixelWindow(5,0).lastTick(),"repeated tile boundary may conservatively recheck");
        var repeated=sprite("{\"animation\":{\"interpolate\":true,\"frames\":[1,1,1]}}");check(repeated.animated()&&repeated.interpolate()&&!repeated.timeVarying(),"native repeated schedule metadata stays intact while pixel inputs are fixed");equal(new TextureSprite.PixelSample(1,1,0),repeated.pixelWindow(Long.MAX_VALUE,0.5).sample(),"constant nonzero source tile preserved");equal(Long.MAX_VALUE,repeated.pixelWindow(0,0).lastTick(),"fixed schedule interval spans all future ticks");
        for(String metadata:Arrays.asList(null,"{\"animation\":{}}","{\"animation\":{\"frametime\":7,\"interpolate\":true}}","{\"animation\":{\"interpolate\":true,\"frames\":[{\"index\":1,\"time\":3},{\"index\":1,\"time\":7},{\"index\":0,\"time\":11}]}}")) {
            var value=sprite(metadata);for(long tick=0;tick<128;tick++) {
                var window=value.pixelWindow(tick,0.9);equal(tick,window.firstTick(),"window begins at observed exact clock");equal(clockOracle(value,tick),window.sample(),"window sample matches independent linear schedule");equal(clockOracle(value,tick),value.pixelSample(tick,0.1),"optimized pixel sampling preserves independent scheduling");
                for(long probe: new long[]{tick,Math.min(tick+1,window.lastTick()),tick+(window.lastTick()-tick)/2,window.lastTick()})equal(window.sample(),clockOracle(value,probe),"entire conservative interval preserves canonical samples");
            }
        }
        var longFrame=sprite("{\"animation\":{\"frametime\":2147483647,\"interpolate\":true}}");equal(2147483L,longFrame.pixelWindow(0,0).lastTick(),"float quantization first plateau has independent known boundary");equal(new TextureSprite.PixelSample(0,1,1),longFrame.pixelWindow(2147484,0).sample(),"first nonzero thousandth boundary");
        for(var value:List.of(longFrame,stepped,repeated))for(long tick:new long[]{2147483646L,2147483647L,Long.MAX_VALUE-1,Long.MAX_VALUE}) { var window=value.pixelWindow(tick,0);check(window.lastTick()>=tick,"large clock interval never overflows");equal(clockOracle(value,tick),window.sample(),"large clock modulo matches linear oracle");equal(window.sample(),clockOracle(value,window.lastTick()),"large endpoint remains inside same canonical inputs"); }
        equal(Long.MAX_VALUE,longFrame.pixelWindow(Long.MAX_VALUE-1,0).lastTick(),"future interval saturates at maximum clock");throwsType(IllegalArgumentException.class,()->longFrame.pixelWindow(-1,0),"negative window clock rejected");throwsType(IllegalArgumentException.class,()->longFrame.pixelWindow(0,Double.NaN),"nonfinite window partial rejected");throwsType(IllegalArgumentException.class,()->longFrame.pixelWindow(0,1),"exclusive partial endpoint rejected");
    }
    private static void sparsePreflight() {
        var layout=layout(4096,512);equal(Set.of(ANIMATED),layout.timeVaryingSprites().keySet(),"clock index excludes fixed sprite");check(layout.timeVaryingSprites().get(ANIMATED)==layout.sprites().get(ANIMATED),"clock index shares exact placement identity");throwsType(UnsupportedOperationException.class,()->layout.timeVaryingSprites().clear(),"clock index is immutable");
        var raster=baseline(layout);for(long tick:new long[]{0,1,2,3,4,Long.MAX_VALUE}) {
            var request=AtlasFrameWorker.Request.capture(raster,tick,0);var update=AtlasImages.updates(raster,tick,0,()->false);var plan=AtlasFramePlan.prepare(raster,update,()->false);check(!plan.requiresPlaceholder(),"sparse preflight has complete CPU plan");equal(update.patches().size(),request.changedSprites(),"sparse changed count agrees with complete patch pass");equal(plan.extraCpuBytes(),request.extraCpuBytes(),"sparse page/patch payload agrees with actual plan");equal(layout.sprites().keySet(),plan.raster().orElseThrow().samples().keySet(),"output sample receipt remains complete");equal(raster.samples().get(STATIC),plan.raster().orElseThrow().samples().get(STATIC),"static canonical input remains intact");
        }
        var slow=baseline(layout(4096,512,"{\"animation\":{\"frametime\":10000,\"interpolate\":true}}"));var atZero=AtlasFrameWorker.Request.capture(slow,0,0.9);equal(9L,atZero.lastTick(),"atlas interval is minimum varying-sprite plateau");check(atZero.matchesClock(9,0)&&!atZero.matchesClock(10,0),"atlas proof admits only proven forward plateau");var later=AtlasFrameWorker.Request.capture(slow,10,0);check(!later.matchesClock(9,0),"backward seek requires fresh dynamic observation");check(later.matchesClock(19,0.99)&&!later.matchesClock(20,0),"quantized interpolation boundary invalidates proof");
        var mixed=baseline(layout(4096,512,"{\"animation\":{\"frametime\":10000,\"interpolate\":true}}","{\"animation\":{\"frametime\":3000,\"interpolate\":true}}"));equal(2,mixed.layout().timeVaryingSprites().size(),"multiple varying slots are indexed");equal(2L,AtlasFrameWorker.Request.capture(mixed,0,0).lastTick(),"atlas proof stops at earliest distinct sprite boundary");
        for(long tick:new long[]{3,10}) { var request=AtlasFrameWorker.Request.capture(mixed,tick,0);var plan=AtlasFramePlan.prepare(mixed,AtlasImages.updates(mixed,tick,0,()->false),()->false);equal(tick==3?1:2,request.changedSprites(),"independent sprite durations produce exact changed count");equal(plan.extraCpuBytes(),request.extraCpuBytes(),"page-copy preflight remains exact for multiple varying slots"); }
        throwsType(IllegalArgumentException.class,()->atZero.matchesClock(0,Double.POSITIVE_INFINITY),"proof cannot bypass clock validation");throwsType(IllegalArgumentException.class,()->atZero.matchesClock(-1,0),"proof rejects negative clock");check(!atZero.samePixels(AtlasFrameWorker.Request.capture(baseline(slow.layout()),0,0)),"proof retains exact-raster identity guard");
    }
    private static void staticAtlasWorkers() {
        for(String metadata:Arrays.asList(null,"{\"animation\":{\"interpolate\":true,\"frames\":[1,1]}}")) {
            var layout=layout(4096,512,metadata);check(layout.timeVaryingSprites().isEmpty(),"all fixed inputs produce empty clock index");var raster=baseline(layout);var request=AtlasFrameWorker.Request.capture(raster,Long.MAX_VALUE,0.5);equal(0,request.changedSprites(),"static request changes no sprite");equal(0L,request.extraCpuBytes(),"static preflight has no patch/page payload");equal(layout.usage().orElseThrow().knownPayloadBytes(),request.reservation(),"direct worker still admits retained source even for no-op output");check(request.matchesClock(0,0)&&request.matchesClock(Long.MAX_VALUE,0.9),"static proof can accept forward/backward clocks");
            var executor=new QueuedExecutor();try(var memory=new RenderMemory(new RenderMemory.Limits(4096,8));var worker=new AtlasFrameWorker(4096,memory,()->executor)) {
                long revision=worker.submit(request);executor.runAll();try(var complete=worker.take(revision).orElseThrow()) { var output=complete.plan().raster().orElseThrow();equal(layout.sprites().keySet(),output.samples().keySet(),"direct no-op worker preserves complete sample map");equal(raster.samples(),output.samples(),"fixed nonzero tiles survive full worker handoff");equal(0L,complete.plan().extraCpuBytes(),"no-op worker output matches sparse reservation");for(int page=0;page<raster.pages().size();page++)check(raster.pages().get(page)==output.pages().get(page),"no-op worker shares unchanged pages"); }
                equal(0L,worker.reservedBytes(),"no-op completion ownership releases worker charge");equal(0L,memory.stats().chargedBytes(),"static handoff releases shared admission");
            }
        }
    }
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
    private static AtlasUpdate dirty(AtlasRaster source,long tick) { return AtlasImages.updates(source,tick,0,AtlasImages.PatchMode.DIRTY_RECTANGLES,()->false); }
    private static void sameRaster(AtlasRaster expected,AtlasRaster actual,String message) {
        check(!expected.requiresPlaceholder()&&!actual.requiresPlaceholder(),message+" complete rasters");equal(expected.samples(),actual.samples(),message+" canonical samples");equal(expected.tick(),actual.tick(),message+" exact clock");
        for(int page=0;page<expected.pages().size();page++)check(Arrays.equals(expected.pages().get(page).copyPixels(),actual.pages().get(page).copyPixels()),message+" page "+page);
    }
    private static void dirtyPixels() {
        for(int[] indices:new int[][]{{5},{0},{15},{5,10},{6}}) {
            var source=baseline(patterned(8192,1024,false,indices));var slot=source.layout().sprites().get(ANIMATED);var change=dirty(source,2);check(!change.requiresPlaceholder(),"bounded rectangle preparation");equal(1,change.patches().size(),"one rectangle per changed sprite");var patch=change.patches().getFirst();
            int x=indices[0]==0?-1:indices[0]==15?3:indices[0]==6?2:1,y=indices[0]==0?-1:indices[0]==15?3:1;int size=indices.length==2?2:indices[0]==5||indices[0]==6?1:2;
            equal(slot.x()+x,patch.x(),"nonzero content or extruded corner X");equal(slot.y()+y,patch.y(),"nonzero content or extruded corner Y");equal(size,patch.pixels().width(),"minimal bounding width");equal(size,patch.pixels().height(),"minimal bounding height");equal((long)size*size*4,change.bytes(),"tight patch payload");
            if(indices.length==2)equal(0x203040ff,patch.pixels().rgba(1,0),"enclosing sparse rectangle preserves unchanged interior");
            var plan=AtlasFramePlan.prepare(source,change,()->false);var full=AtlasImages.capture(source.layout(),2,0,()->false);sameRaster(full,plan.raster().orElseThrow(),"dirty/full recapture");sameRaster(full,AtlasImages.apply(source,AtlasImages.updates(source,2,0,()->false),()->false),"whole-slot reference");
            equal(slot.paddedBytes(),AtlasImages.updates(source,2,0,()->false).bytes(),"compatibility overload keeps full slot");equal(source.layout().pages().get(slot.page()).bytes(),plan.copiedBytes(),"changed page remains a complete immutable copy");
            check(source.pages().get(slot.page())!=plan.raster().orElseThrow().pages().get(slot.page()),"changed page is independent");equal(0x203040ff,content(source,ANIMATED),"base raster unchanged");
            int fixed=source.layout().sprites().get(STATIC).page();check(source.pages().get(fixed)==plan.raster().orElseThrow().pages().get(fixed),"static page shared");
            var stale=AtlasImages.apply(baseline(source.layout()),change,()->false);check(stale.requiresPlaceholder()&&stale.problems().getFirst().code()==ModelPlan.Code.STALE_ATLAS,"subrectangle still requires exact raster");
            var bad=new AtlasUpdate.Patch(ANIMATED,slot.page(),slot.x()+slot.width()+slot.gutter(),slot.y(),1,RgbaImage.owned(1,1,new byte[4]));
            var rejected=AtlasImages.apply(source,AtlasUpdate.success(source,2,0,List.of(bad),change.samples(),4),()->false);check(rejected.requiresPlaceholder()&&rejected.pages().isEmpty()&&rejected.problems().getFirst().code()==ModelPlan.Code.ATLAS,"rectangle beyond slot rejected atomically");
        }
        var interpolated=baseline(patterned(8192,1024,true,5));for(long tick:new long[]{1,2,3,4,Long.MAX_VALUE})sameRaster(AtlasImages.capture(interpolated.layout(),tick,0,()->false),AtlasImages.apply(interpolated,dirty(interpolated,tick),()->false),"interpolated rectangle oracle");
        throwsType(NullPointerException.class,()->AtlasImages.updates(interpolated,1,0,null,()->false),"typed mode is required");
    }
    private static void dirtyReceipts() {
        var identical=baseline(patterned(8192,0,false));var change=dirty(identical,2);equal(0L,change.bytes(),"different tiles with identical pixels need no payload");equal(1,change.samples().get(ANIMATED).currentFrame(),"new tile receipt retained");check(!change.samples().equals(identical.samples()),"visually identical inputs are not falsely relabelled");
        var plan=AtlasFramePlan.prepare(identical,change,()->false);equal(0L,plan.extraCpuBytes(),"identical output copies no pages");equal(List.of(),plan.changedPages(),"no changed page indices");var output=plan.raster().orElseThrow();equal(2L,output.tick(),"metadata-only frame advances exact clock");equal(identical.layout().sprites().keySet(),output.samples().keySet(),"static receipt stays complete");for(int i=0;i<output.pages().size();i++)check(output.pages().get(i)==identical.pages().get(i),"identical output shares all pages");
        int[] colors=new int[32];Arrays.fill(colors,0x203040ff);colors[21]=0x213040ff;var rounded=baseline(layout(8192,1024,"{\"animation\":{\"frametime\":1000,\"interpolate\":true}}",null,4,colors));
        var plateau=dirty(rounded,1);check(!plateau.samples().equals(rounded.samples()),"progress sample actually changed");equal(0L,plateau.bytes(),"RGBA rounding plateau has no upload");sameRaster(AtlasImages.capture(rounded.layout(),1,0,()->false),AtlasImages.apply(rounded,plateau,()->false),"rounded plateau receipt");equal(4L,dirty(rounded,500).bytes(),"rounded channel transition becomes dirty");
    }
    private static void dirtyAdmission() {
        var source=baseline(patterned(8192,4,false,5));long base=source.layout().usage().orElseThrow().knownPayloadBytes(),copy=source.layout().pages().get(source.layout().sprites().get(ANIMATED).page()).bytes(),peak=base+copy+4;
        var exact=baseline(patterned(peak,4,false,5));var request=AtlasFrameWorker.Request.capture(exact,2,0,AtlasImages.PatchMode.DIRTY_RECTANGLES);equal(peak,request.reservation(),"exact dirty peak reserved");equal(AtlasImages.PatchMode.DIRTY_RECTANGLES,request.mode(),"request strategy retained");equal(1,request.changedSprites(),"metadata candidates stay conservative");throwsType(IllegalArgumentException.class,()->AtlasFrameWorker.Request.capture(exact,2,0),"full-slot mode keeps exact preflight rejection");check(!AtlasFramePlan.prepare(exact,dirty(exact,2),()->false).requiresPlaceholder(),"exact dirty peak fits");
        var lower=baseline(patterned(peak-1,4,false,5));var lowerRequest=AtlasFrameWorker.Request.capture(lower,2,0,AtlasImages.PatchMode.DIRTY_RECTANGLES);equal(peak-1,lowerRequest.reservation(),"dirty upper bound respects working ceiling");var failed=AtlasFramePlan.prepare(lower,dirty(lower,2),()->false);check(failed.requiresPlaceholder()&&failed.raster().isEmpty()&&failed.problems().getFirst().code()==ModelPlan.Code.LIMIT,"one-byte copy deficit fails without partial pages");
        var narrow=baseline(patterned(8192,3,false,5));var rejected=dirty(narrow,2);check(rejected.requiresPlaceholder()&&rejected.bytes()==0&&rejected.patches().isEmpty(),"one-byte patch deficit fails atomically");
        for(var baseline:List.of(source,baseline(patterned(8192,1024,false,5)),baseline(patterned(8192,1024,false)),lower,narrow)) {
            var candidate=AtlasFrameWorker.Request.capture(baseline,2,0,AtlasImages.PatchMode.DIRTY_RECTANGLES);try(var memory=new RenderMemory(new RenderMemory.Limits(16384,8));var executor=new QueuedExecutor();var worker=new AtlasFrameWorker(16384,memory,()->executor)) {
                long revision=worker.submit(candidate);equal(candidate.reservation(),worker.reservedBytes(),"queued upper bound held");equal(candidate.reservation(),memory.stats().chargedBytes(),"shared admission holds upper bound");executor.runAll();
                if(baseline==lower||baseline==narrow) { equal(AtlasFrameWorker.Phase.FAILED,worker.status().phase(),"worker publishes exact budget failure");equal(ModelPlan.Code.LIMIT,worker.status().problems().getFirst().code(),"structured worker limit");check(worker.take(revision).isEmpty(),"failed worker yields no output");equal(0L,worker.reservedBytes(),"failed worker releases upper bound"); }
                else { long actual=baseline.layout().usage().orElseThrow().knownPayloadBytes()+AtlasFramePlan.prepare(baseline,dirty(baseline,2),()->false).extraCpuBytes();equal(actual,worker.reservedBytes(),"READY shrinks to actual retained pages/patches");equal(actual,memory.stats().chargedBytes(),"shared charge shrinks too");try(var complete=worker.take(revision).orElseThrow();var input=complete.retainInput()) { complete.close();worker.close();equal(actual,memory.stats().chargedBytes(),"independent input survives worker/completion closure");sameRaster(AtlasImages.capture(baseline.layout(),2,0,()->false),input.value().raster().orElseThrow(),"owned worker output"); } }
                equal(0L,memory.stats().chargedBytes(),"final charge released");equal(0,memory.stats().accounts(),"no admission leak");
            }
        }
        throwsType(CancellationException.class,()->AtlasImages.updates(source,2,0,AtlasImages.PatchMode.DIRTY_RECTANGLES,()->true),"cancel before dirty scan");int[] polls={0};throwsType(CancellationException.class,()->AtlasImages.updates(source,2,0,AtlasImages.PatchMode.DIRTY_RECTANGLES,()->++polls[0]>=5),"cancel within dirty row scan");equal(0x203040ff,content(source,ANIMATED),"cancellation preserves source");
    }
    public static void main(String[] arguments) {
        if(arguments.length!=0)throw new IllegalArgumentException("No arguments supported");
        group("animation and texture metadata",AnimationCpuContracts::metadata);
        group("zero and lone-frame static behavior",AnimationCpuContracts::loneFrames);
        group("integer repeated-frame scheduling",AnimationCpuContracts::schedule);
        group("animation metadata and clock guards",AnimationCpuContracts::metadataGuards);
        group("canonical pixel sample inputs",AnimationCpuContracts::canonicalSamples);
        group("conservative canonical clock windows",AnimationCpuContracts::clockWindows);
        group("sparse atlas animation preflight",AnimationCpuContracts::sparsePreflight);
        group("static atlas worker receipts",AnimationCpuContracts::staticAtlasWorkers);
        group("independent RGBA interpolation patches",AnimationCpuContracts::patchPixels);
        group("same-pixel clock coalescing",AnimationCpuContracts::noOpClocks);
        group("copy-on-write pages and accounting",AnimationCpuContracts::pageSharing);
        group("exact stale baseline rejection",AnimationCpuContracts::staleBaseline);
        group("atlas working limits and cancellation",AnimationCpuContracts::limitsAndCancellation);
        group("independent atlas completion inputs",AnimationCpuContracts::retainedCompletion);
        group("minimal atlas rectangles and full-frame equivalence",AnimationCpuContracts::dirtyPixels);
        group("identical pixels and exact animation receipts",AnimationCpuContracts::dirtyReceipts);
        group("dirty atlas admission and retained worker payload",AnimationCpuContracts::dirtyAdmission);
        System.out.println("PASS "+groups+" synthetic animation groups; no native/GPU parity is claimed.");
    }
}
