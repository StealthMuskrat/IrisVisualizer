package dev.irisvisualizer.renderer;

import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Synthetic world pipeline contracts. No client archive is opened and no native parity is claimed. */
public final class WorldCpuContracts {
    private static final BooleanSupplier LIVE=()->false;
    private static int groups;
    private WorldCpuContracts() {}
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void equal(Object expected,Object actual,String message) { check(Objects.equals(expected,actual),message+": expected "+expected+", got "+actual); }
    private static void near(double expected,double actual,String message) { check(Double.isFinite(actual)&&Math.abs(expected-actual)<1e-6,message+": expected "+expected+", got "+actual); }
    private static <T extends Throwable> void throwsType(Class<T> type,Runnable action,String message) {
        try { action.run(); }catch(Throwable failure) { if(type.isInstance(failure))return;throw new AssertionError(message+": wrong exception",failure); }
        throw new AssertionError(message+": exception missing");
    }
    private static void group(String name,Runnable action) { action.run();groups++;System.out.println("PASS "+name); }
    private static final AssetId BLOCK=new AssetId("fixture","cube"),MODEL=new AssetId("fixture","block/cube"),SPRITE=new AssetId("fixture","block/pixel");
    private static RenderBlockState state(int nativeId) { return new RenderBlockState(nativeId,"fixture:cube[native_exact=retained]",BLOCK,Map.of("native_exact","retained"),Map.of("native_exact",Set.of("retained","other"))); }
    private static final WorldSnapshot.Biome BIOME=new WorldSnapshot.Biome(731,"fixture:original_biome",new AssetId("fixture","original_biome"));
    private static WorldSnapshot snapshot(int width) {
        return WorldSnapshot.capture(new WorldSnapshot.Source(UUID.randomUUID(),"0".repeat(64),"synthetic-world-contracts",BlockModels.PROFILE,AssetId.minecraft("overworld"),Long.MAX_VALUE),new WorldSnapshot.Extent(9_007_199_254_740_993L,-64,-9_007_199_254_740_993L,width,1,1),List.of(state(407)),List.of(BIOME),new int[width],new int[width],LIVE);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static void chunk(DataOutputStream output,String name,byte[] bytes) throws IOException {
        byte[] type=name.getBytes(StandardCharsets.US_ASCII);var crc=new CRC32();crc.update(type);crc.update(bytes);output.writeInt(bytes.length);output.write(type);output.write(bytes);output.writeInt((int)crc.getValue());
    }
    private static byte[] pixel(int alpha) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeLong(0x89504e470d0a1a0aL);
            var headerBytes=new ByteArrayOutputStream();var header=new DataOutputStream(headerBytes);header.writeInt(1);header.writeInt(1);header.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",headerBytes.toByteArray());
            var compressed=new ByteArrayOutputStream();try(var zlib=new DeflaterOutputStream(compressed)) { zlib.write(new byte[]{0,10,20,30,(byte)alpha}); }chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return bytes.toByteArray();
        }catch(IOException impossible) { throw new AssertionError(impossible); }
    }
    private static ClientAssetSnapshot assets(int alpha) {
        var values=new TreeMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset>();
        String blockstate="{\"variants\":{\"\":{\"model\":\"fixture:block/cube\"}}}";
        var faces=new StringJoiner(",");for(var direction:ModelPlan.Direction.values()) { String name=direction.name().toLowerCase(Locale.ROOT);faces.add("\""+name+"\":{\"texture\":\"#all\",\"cullface\":\""+name+"\"}"); }
        String model="{\"textures\":{\"all\":\"fixture:block/pixel\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"+faces+"}}]}";
        add(values,ClientAssetSnapshot.Kind.BLOCKSTATE,BLOCK,blockstate.getBytes(StandardCharsets.UTF_8));add(values,ClientAssetSnapshot.Kind.MODEL,MODEL,model.getBytes(StandardCharsets.UTF_8));add(values,ClientAssetSnapshot.Kind.TEXTURE,SPRITE,pixel(alpha));
        long count=values.values().stream().mapToLong(ClientAssetSnapshot.Asset::bytes).sum();
        // Explicit test-only detached catalog. This is not a selected-client verification receipt.
        var receipt=new ClientAssetSnapshot.Receipt(Path.of("synthetic-world-fixture"),digest((model+alpha).getBytes(StandardCharsets.UTF_8)),count,BlockModels.PROFILE,BlockModels.PROFILE,3,0,count);
        return new ClientAssetSnapshot(receipt,values);
    }
    private static void add(Map<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset> values,ClientAssetSnapshot.Kind kind,AssetId id,byte[] bytes) {
        values.put(new ClientAssetSnapshot.Key(kind,id),ClientAssetSnapshot.Asset.owned("synthetic/"+kind+"/"+id,bytes,digest(bytes)));
    }
    private record Fixture(WorldSnapshot snapshot,ClientAssetSnapshot assets,BlockMesh block,TexturePreparation textures,AtlasLayout layout,AtlasMesh prototype,WorldModels models,WorldVisibility visibility,WorldScenePlan plan,AtlasRaster raster) {}
    private static Fixture fixture(int width,int alpha,boolean nativeCull) {
        var snapshot=snapshot(width);var assets=assets(alpha);var model=BlockModels.resolve(assets,snapshot.states().getFirst(),LIVE);check(!model.requiresPlaceholder(),"synthetic model resolved: "+model.problems());
        var block=GeometryBaker.bake(model,new int[]{0},LIVE);check(!block.requiresPlaceholder(),"synthetic cube baked: "+block.problems());
        var textures=TextureMaterials.prepare(assets,block,LIVE);check(!textures.requiresPlaceholder(),"synthetic texture prepared: "+textures.problems());
        var limits=new AtlasBuilder.Limits(1,1,6,16,8,1,1,1024,4096,1024,4096,1024,16384,1024);
        var layout=AtlasBuilder.plan(List.of(textures),limits,LIVE);check(!layout.requiresPlaceholder(),"synthetic atlas planned: "+layout.problems());
        var prototype=AtlasMeshes.bind(layout,textures,LIVE);check(!prototype.requiresPlaceholder(),"synthetic prototype bound: "+prototype.problems());
        var models=WorldModels.capture(snapshot,layout,List.of(WorldModels.Entry.from(prototype)),new int[width],LIVE);check(!models.requiresPlaceholder(),"world assignments complete: "+models.problems());
        var mask=new byte[width];if(nativeCull)for(int x=0;x<width;x++) { if(x>0)mask[x]|=1<<ModelPlan.Direction.WEST.ordinal();if(x+1<width)mask[x]|=1<<ModelPlan.Direction.EAST.ordinal(); }
        var visibility=nativeCull?WorldVisibility.capture(snapshot,layout,"supplied synthetic neighbor masks",mask,LIVE):WorldVisibility.keepAll(snapshot,layout);
        var sceneLimits=new WorldScenePlan.Limits(1,64,1024,64,64,1024*1024,4*1024*1024);var plan=WorldScenePlan.plan(models,visibility,sceneLimits,WorldMesher.Limits.DEFAULT,LIVE);check(!plan.requiresPlaceholder(),"complete scene plan: "+plan.problems());
        var raster=AtlasImages.capture(layout,0,0,LIVE);check(!raster.requiresPlaceholder(),"complete atlas raster");return new Fixture(snapshot,assets,block,textures,layout,prototype,models,visibility,plan,raster);
    }
    private static WorldScene.Result build(Fixture fixture,WorldSectionCache cache) {
        var owner=WorldScene.build(fixture.plan(),fixture.raster(),cache,LIVE);check(!owner.requiresPlaceholder(),"complete scene: "+owner.problems());return owner;
    }
    private static WorldRay ray(WorldSnapshot snapshot) { return new WorldRay(snapshot.extent().position(0),0.5,0.5,3,0,0,-1,0,10); }
    private static void nativeSnapshot() {
        var state=state(407);var source=snapshot(1).source();var states=new int[]{0,0};var biomes=new int[]{0,0};var world=WorldSnapshot.capture(source,new WorldSnapshot.Extent(4,-64,8,2,1,1),List.of(state),List.of(BIOME),states,biomes,LIVE);states[0]=99;biomes[1]=99;
        equal(state,world.state(0),"exact native state");equal(407,world.state(0).nativeStateId(),"native numeric state ID");equal(BIOME,world.biome(1),"exact native biome");equal(16L,world.indexBytes(),"two complete index arrays");
        var bad=WorldSnapshot.capture(source,world.extent(),List.of(state),List.of(BIOME),new int[]{0,1},new int[2],LIVE);check(bad.requiresPlaceholder()&&bad.cellCount()==0&&bad.states().isEmpty()&&bad.biomes().isEmpty(),"bad palette index exposes no partial world");
        var duplicate=WorldSnapshot.capture(source,world.extent(),List.of(state,state),List.of(BIOME),new int[2],new int[2],LIVE);check(duplicate.requiresPlaceholder(),"duplicate native IDs rejected");
    }
    private static void geometry() {
        var fixture=fixture(1,255,false);var packet=fixture.block().packet().orElseThrow();equal(6,packet.faces().size(),"six source cube faces");var vertices=packet.vertexBuffer();var indices=packet.indexBuffer();
        for(int face=0;face<6;face++) {
            int a=indices.get(face*6)*8,b=indices.get(face*6+1)*8,c=indices.get(face*6+2)*8;double ex=vertices.get(b)-vertices.get(a),ey=vertices.get(b+1)-vertices.get(a+1),ez=vertices.get(b+2)-vertices.get(a+2),fx=vertices.get(c)-vertices.get(a),fy=vertices.get(c+1)-vertices.get(a+1),fz=vertices.get(c+2)-vertices.get(a+2);
            double nx=ey*fz-ez*fy,ny=ez*fx-ex*fz,nz=ex*fy-ey*fx;near(1,nx*vertices.get(a+3)+ny*vertices.get(a+4)+nz*vertices.get(a+5),"independent outward winding/normal");
            for(int corner=0;corner<4;corner++)for(int axis=0;axis<3;axis++) { double value=vertices.get((face*4+corner)*8+axis);check(value==0||value==1,"cube boundary coordinates"); }
        }
        equal(fixture.snapshot().states().getFirst(),packet.state(),"geometry retains exact native state");
    }
    private static void atlas() {
        var f=fixture(1,255,false);var slot=f.layout().sprites().get(SPRITE);var image=f.raster().pages().get(slot.page());
        for(int y=slot.y()-slot.gutter();y<slot.y()+1+slot.gutter();y++)for(int x=slot.x()-slot.gutter();x<slot.x()+1+slot.gutter();x++)equal(0x0a141eff,image.rgba(x,y),"independent edge/corner extrusion");
        check(f.prototype().layout()==f.layout(),"exact atlas binding");equal(6,f.prototype().faces().size(),"all faces bound");for(var face:f.prototype().faces())equal(TexturePreparation.AlphaClass.OPAQUE,face.alpha(),"opaque source classification");
        var foreign=AtlasBuilder.plan(List.of(f.textures()),f.layout().limits(),LIVE);check(AtlasMeshes.bind(foreign,fixture(1,255,false).textures(),LIVE).requiresPlaceholder(),"matching-looking foreign preparation rejected");
    }
    private static void assignments() {
        var f=fixture(2,255,false);var wrong=WorldModels.capture(f.snapshot(),f.layout(),List.of(WorldModels.Entry.empty(state(999))),new int[2],LIVE);check(wrong.requiresPlaceholder()&&wrong.entries().isEmpty()&&wrong.indexBytes()==0,"foreign native state assignment atomic");
        var choices=new int[2];var models=WorldModels.capture(f.snapshot(),f.layout(),List.of(WorldModels.Entry.from(f.prototype())),choices,LIVE);choices[0]=999;equal(0,models.entryIndex(0),"per-cell assignments copied");
        var offsets=WorldOffsets.none(snapshot(2));var mixed=WorldModels.capture(f.snapshot(),f.layout(),List.of(WorldModels.Entry.from(f.prototype())),new int[2],offsets,WorldModels.Limits.DEFAULT,LIVE);check(mixed.requiresPlaceholder(),"same receipt metadata does not permit another snapshot's offsets");
    }
    private static void visibility() {
        var kept=fixture(2,255,false);var culled=fixture(2,255,true);equal(12,kept.plan().usage().orElseThrow().faces(),"keep-all does not infer solid adjacency");equal(10,culled.plan().usage().orElseThrow().faces(),"explicit native masks remove shared faces");
        var flags=new byte[]{(byte)(1<<ModelPlan.Direction.EAST.ordinal()),0};var captured=WorldVisibility.capture(kept.snapshot(),kept.layout(),"synthetic",flags,LIVE);flags[0]=0;check(captured.hidden(0,ModelPlan.Direction.EAST),"visibility mask copied");
        var bad=WorldVisibility.capture(kept.snapshot(),kept.layout(),"synthetic",new byte[]{64,0},LIVE);check(bad.requiresPlaceholder(),"undefined mask bits rejected");
    }
    private static void meshReceipts() {
        var f=fixture(2,128,true);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            var scene=owner.scene().orElseThrow();var usage=scene.usage();equal(2,usage.sections(),"one-cell section coverage");equal(2L,usage.coveredCells(),"complete original extent");equal(10,usage.translucentFaces(),"prepared translucent total");equal(0,usage.tintedFaces(),"no invented native tint");equal(2,usage.drawableSections(),"drawable metadata");equal(2,usage.faceBatches(),"prepared face batches");
            int count=0;for(var mesh:scene.sections()) { count+=mesh.faces().size();equal(mesh.faces().size(),mesh.translucentCount(),"section translucent metadata");for(int face=0;face<mesh.faces().size();face++) { equal(f.snapshot().states().getFirst(),mesh.stateForFace(face),"face original state");equal(BIOME,mesh.biomeForFace(face),"face original biome"); } }
            equal(10,count,"complete emitted coverage");
        }
    }
    private static void completeLimits() {
        var f=fixture(2,255,false);var lower=new WorldScenePlan.Limits(1,2,11,2,0,1024*1024,4*1024*1024);var plan=WorldScenePlan.plan(f.models(),f.visibility(),lower,WorldMesher.Limits.DEFAULT,LIVE);
        check(plan.requiresPlaceholder()&&plan.sections().isEmpty()&&plan.usage().isEmpty(),"one-face deficit withholds entire scene plan");equal(WorldProblem.Code.LIMIT,plan.problems().getFirst().code(),"scene face limit diagnostic");
        var foreign=WorldVisibility.keepAll(snapshot(2),f.layout());var mismatch=WorldScenePlan.plan(f.models(),foreign,LIVE);check(mismatch.requiresPlaceholder()&&mismatch.sections().isEmpty(),"exact visibility snapshot required");
        throwsType(CancellationException.class,()->WorldScenePlan.plan(f.models(),f.visibility(),()->true),"scene planning cancellation separate");
    }
    private static void cacheOwners() {
        var f=fixture(2,255,false);try(var cache=new WorldSectionCache()) {
            var first=build(f,cache);var retained=first.retain();var second=build(f,cache);var scene=first.scene().orElseThrow();check(scene.sections().getFirst()==second.scene().orElseThrow().sections().getFirst(),"same epoch shares cached mesh");equal(4L,cache.stats().leases(),"two builds own four section tickets");
            first.close();equal(4L,cache.stats().leases(),"retained scene shares existing ticket owner");second.close();equal(2L,cache.stats().leases(),"second build releases its tickets");check(!cache.clear(),"pinned clear rejected");retained.close();equal(0L,cache.stats().leases(),"last scene releases tickets");check(cache.clear(),"unleased cache clear");equal(0L,cache.stats().knownPayloadBytes(),"clear releases source/mesh demand");
        }
    }
    private static void pinnedReplacement() {
        var first=fixture(2,255,false);var next=fixture(2,255,false);var limits=new WorldSectionCache.Limits(2,1024,64,64,1024*1024,4*1024*1024);
        try(var cache=new WorldSectionCache(limits);var owner=build(first,cache)) {
            var before=cache.stats();var rejected=WorldScene.build(next.plan(),next.raster(),cache,LIVE);check(rejected.requiresPlaceholder(),"pinned replacement refuses incomplete fit");equal(WorldProblem.Code.CACHE_BUSY,rejected.problems().getFirst().code(),"pinned cache diagnostic");equal(before,cache.stats(),"rejection preserves cache and old leases");rejected.close();
            owner.close();try(var accepted=build(next,cache)) { check(accepted.scene().orElseThrow().plan().models()==next.models(),"replacement after releasing old scene"); }
        }
    }
    private static void picking() {
        var f=fixture(1,255,false);try(var cache=new WorldSectionCache();var owner=build(f,cache);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
            check(!index.requiresPlaceholder(),"complete index");var ray=ray(f.snapshot());var hit=index.query(ray,LIVE).hit().orElseThrow();equal(f.snapshot().extent().position(0),hit.position(),"exact large native coordinate");equal(state(407),hit.state(),"picked original state");equal(BIOME,hit.biome(),"picked original biome");near(2,hit.distance(),"analytic unit-cube front plane");check(!hit.placeholder()&&hit.surface().isPresent(),"actual geometry hit");
            var exhaustive=index.query(ray,WorldPickIndex.Options.DEFAULT.withBatchCulling(false),null,LIVE);equal(hit,exhaustive.hit().orElseThrow(),"batch/exhaustive same hit");
            var limited=index.query(ray,new WorldPickIndex.Options(100,0,0,false,WorldPickIndex.Alpha.GEOMETRY),null,LIVE);check(limited.requiresPlaceholder()&&limited.hit().isEmpty(),"no partial nearest hit on budget failure");
            var clip=new WorldClip(new WorldSnapshot.Extent(f.snapshot().extent().minX()+4,-64,f.snapshot().extent().minZ(),1,1,1));check(index.query(ray,WorldPickIndex.Options.DEFAULT.withClip(clip),null,LIVE).hit().isEmpty(),"clipped source excluded");
        }
        var transparent=fixture(1,0,false);try(var cache=new WorldSectionCache();var owner=build(transparent,cache);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
            check(index.query(ray(transparent.snapshot()),LIVE).hit().isEmpty(),"displayed transparent pixels reject hit");check(index.query(ray(transparent.snapshot()),new WorldPickIndex.Options(100,100,0,false,WorldPickIndex.Alpha.GEOMETRY),null,LIVE).hit().isPresent(),"geometry-only mode retains transparent surface");
        }
    }
    private static void ordering() {
        var f=fixture(2,128,false);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            var scene=owner.scene().orElseThrow();var view=new WorldDrawOrder.View(f.snapshot().extent().position(0),0.5,0.5,4,0.5,0.5,0);var order=WorldDrawOrder.prepare(scene,view,LIVE);check(!order.requiresPlaceholder(),"complete global order");
            record Depth(long key,double value) {}var expected=new ArrayList<Depth>();
            for(int section=0;section<scene.sections().size();section++) { var mesh=scene.sections().get(section);var vertices=mesh.vertexBuffer();for(int face=0;face<mesh.faces().size();face++) { double z=0;for(int corner=0;corner<4;corner++)z+=vertices.get((face*4+corner)*8+2);double depth=4-(mesh.origin().z()-view.anchor().z()+z/4);expected.add(new Depth(((long)section<<32)|face,depth)); } }
            expected.sort(Comparator.comparingDouble(Depth::value).reversed().thenComparingLong(Depth::key));equal(expected.size(),order.size(),"all translucent faces ordered");for(int at=0;at<order.size();at++)equal(expected.get(at).key(),((long)order.section(at)<<32)|order.face(at),"independent centroid stable order");
            long source=scene.usage().knownPayloadBytes();equal(source+12*32,WorldDrawOrder.reservation(scene,WorldDrawOrder.Limits.DEFAULT),"verified count sizes scratch");var failed=WorldDrawOrder.prepare(scene,view,new WorldDrawOrder.Limits(12,383,source+384),LIVE);check(failed.requiresPlaceholder()&&failed.size()==0,"insufficient sort scratch is atomic");
        }
    }
    /** Read only the private executor to gate scheduling; worker state and admission are never patched. */
    private static ExecutorService orderExecutor(WorldOrderWorker worker) {
        try { var field=WorldOrderWorker.class.getDeclaredField("executor");field.setAccessible(true);return (ExecutorService)field.get(worker); }
        catch(ReflectiveOperationException failure) { throw new AssertionError("Cannot schedule deterministic sorting contracts",failure); }
    }
    private static final class OrderGate implements AutoCloseable {
        private final CountDownLatch release=new CountDownLatch(1);
        OrderGate(ExecutorService executor) { executor.execute(()->{ try { release.await(); }catch(InterruptedException cancelled) { Thread.currentThread().interrupt(); } }); }
        @Override public void close() { release.countDown(); }
    }
    private static void orderBarrier(ExecutorService executor) {
        try { executor.submit(()->{}).get(5,TimeUnit.SECONDS); }
        catch(InterruptedException cancelled) { Thread.currentThread().interrupt();throw new AssertionError("Sorting contract interrupted",cancelled); }
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException failure) { throw new AssertionError("Sorting contract did not unwind",failure); }
    }
    private static WorldDrawOrder.View orderView(Fixture f) { return new WorldDrawOrder.View(f.snapshot().extent().position(0),0.5,0.5,4,0.5,0.5,0); }
    private static void pendingOrderAdmission() {
        var f=fixture(2,128,false);var bigger=fixture(3,128,false);
        try(var cache=new WorldSectionCache();var owner=build(f,cache);var other=build(bigger,cache)) {
            long peak=owner.scene().orElseThrow().usage().knownPayloadBytes()+12*32;
            check(other.scene().orElseThrow().usage().knownPayloadBytes()+18*32>peak,"larger fixture exceeds one shared reservation");
            for(boolean overlap:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(peak*(overlap?2:1),overlap?2:1));var worker=new WorldOrderWorker(4*1024*1024,memory);var gate=new OrderGate(orderExecutor(worker))) {
                var first=owner.retain();long firstRevision=worker.submit(first,orderView(f));var before=worker.status();var second=owner.retain();WorldScene.Result pending;
                if(overlap) {
                    equal(firstRevision+1,worker.submit(second,orderView(f)),"pending replacement accepts one token after overlap admission");check(first.closed()&&!second.closed(),"replacement closes only superseded pending source");pending=second;
                } else {
                    throwsType(RenderMemory.LimitException.class,()->worker.submit(second,orderView(f)),"pending source cleanup cannot supply early replacement credit");check(second.closed()&&!first.closed()&&worker.status()==before,"overlap rejection preserves old pending source and status");pending=first;
                }
                equal(peak,worker.reservedBytes(),"one pending scratch reservation after submission");equal(peak,memory.stats().chargedBytes(),"shared charge released only after old pending cleanup");equal(1,memory.stats().accounts(),"one surviving pending account");
                var retainedStatus=worker.status();var rejected=other.retain();throwsType(RenderMemory.LimitException.class,()->worker.submit(rejected,orderView(bigger)),"oversized replacement rejected atomically");
                check(rejected.closed()&&!pending.closed()&&worker.status()==retainedStatus,"rejection consumes only fresh source and preserves status");equal(peak,worker.reservedBytes(),"rejection preserves pending reservation");equal(peak,memory.stats().chargedBytes(),"rejection preserves shared bytes");
                throwsType(IllegalArgumentException.class,()->worker.submit(pending,orderView(f)),"already-owned wrapper remains protected");check(!pending.closed()&&worker.status()==retainedStatus,"duplicate wrapper rejection preserves pending owner");
                worker.close();check(pending.closed()&&!owner.closed(),"shutdown releases only worker source");equal(0L,worker.reservedBytes(),"pending local charge released");equal(0L,memory.stats().chargedBytes(),"pending shared charge released");equal(0,memory.stats().accounts(),"no pending account leak");
            }
        }
    }
    private static void readyOrderAdmission() {
        var f=fixture(2,128,false);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            long source=owner.scene().orElseThrow().usage().knownPayloadBytes(),peak=source+12*32,retained=source+12*8;
            for(boolean overlap:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(overlap?retained+peak:peak,overlap?2:1));var worker=new WorldOrderWorker(4*1024*1024,memory)) {
                var executor=orderExecutor(worker);var first=owner.retain();long revision=worker.submit(first,orderView(f));orderBarrier(executor);
                equal(WorldOrderWorker.Phase.READY,worker.status().phase(),"complete order ready after worker unwind");equal(retained,worker.reservedBytes(),"ready output counts final keys without sort scratch");equal(retained,memory.stats().chargedBytes(),"ready shared hold shrinks to retained source and keys");
                try(var gate=new OrderGate(executor)) {
                    var before=worker.status();var second=owner.retain();
                    if(overlap) {
                        equal(revision+1,worker.submit(second,orderView(f)),"ready replacement accepts exact old-output plus new-peak overlap");check(first.closed()&&!second.closed()&&worker.take(revision).isEmpty(),"old untaken output dropped only after new admission");equal(peak,worker.reservedBytes(),"new pending scratch replaces ready bytes");equal(peak,memory.stats().chargedBytes(),"old ready account released after source cleanup");
                    } else {
                        throwsType(RenderMemory.LimitException.class,()->worker.submit(second,orderView(f)),"untaken ready source supplies no early cleanup credit");check(second.closed()&&!first.closed()&&worker.status()==before,"ready output and status survive overlap rejection");equal(retained,worker.reservedBytes(),"rejected replacement preserves ready retained bytes");equal(retained,memory.stats().chargedBytes(),"ready shared charge preserved");check(worker.take(revision+1).isEmpty(),"future take cannot transfer old ready output");
                    }
                    equal(1,memory.stats().accounts(),"only surviving source/order account remains");worker.close();check(first.closed()&&second.closed()&&!owner.closed(),"close preserves independently retained original source");equal(0L,worker.reservedBytes(),"ready/pending local reservations released");equal(0L,memory.stats().chargedBytes(),"ready replacement leaves no charge");
                }
            }
        }
    }
    private static void transferredOrderAdmission() {
        var f=fixture(2,128,false);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            long source=owner.scene().orElseThrow().usage().knownPayloadBytes(),peak=source+12*32,retained=source+12*8;
            try(var memory=new RenderMemory(new RenderMemory.Limits(peak,1));var worker=new WorldOrderWorker(4*1024*1024,memory)) {
                var executor=orderExecutor(worker);var first=owner.retain();long revision=worker.submit(first,orderView(f));orderBarrier(executor);
                try(var completion=worker.take(revision).orElseThrow();var gate=new OrderGate(executor)) {
                    equal(0L,worker.reservedBytes(),"transferred output absent from local worker charge");equal(retained,memory.stats().chargedBytes(),"transferred output stays shared-admitted");
                    var before=worker.status();var rejected=owner.retain();throwsType(RenderMemory.LimitException.class,()->worker.submit(rejected,orderView(f)),"caller-owned output supplies no replacement credit");
                    check(rejected.closed()&&!completion.owner().closed()&&worker.status()==before,"external completion survives rejected resubmission");equal(retained,memory.stats().chargedBytes(),"external charge preserved");equal(0L,worker.reservedBytes(),"no rejected pending job");
                    try(var external=completion.admission().retain()) {
                        completion.close();var sharedRejected=owner.retain();throwsType(RenderMemory.LimitException.class,()->worker.submit(sharedRejected,orderView(f)),"independent external hold still supplies no credit");check(sharedRejected.closed()&&worker.status()==before,"shared external rejection preserves worker status");equal(retained,memory.stats().chargedBytes(),"independent hold preserves retained charge");
                    }
                    equal(0L,memory.stats().chargedBytes(),"final external close releases charge");var accepted=owner.retain();equal(revision+1,worker.submit(accepted,orderView(f)),"rejections do not spend worker revision");worker.close();check(accepted.closed()&&!owner.closed(),"later pending source released independently");equal(0L,memory.stats().chargedBytes(),"no external/retry account leak");equal(0,memory.stats().accounts(),"all sort accounts released");
                }
            }
        }
    }
    private static void activeOrderAdmission() {
        var f=fixture(2,128,false);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            long peak=owner.scene().orElseThrow().usage().knownPayloadBytes()+12*32;
            try(var memory=new RenderMemory(new RenderMemory.Limits(peak*2,2));var worker=new WorldOrderWorker(peak,memory)) {
                var executor=orderExecutor(worker);var first=owner.retain();
                // The worker can publish SORTING, then waits for this source monitor before reading it.
                synchronized(first) {
                    worker.submit(first,orderView(f));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while(worker.status().phase()==WorldOrderWorker.Phase.QUEUED&&System.nanoTime()<deadline)try { Thread.sleep(1); }
                    catch(InterruptedException cancelled) { Thread.currentThread().interrupt();throw new AssertionError("Active sorting contract interrupted",cancelled); }
                    equal(WorldOrderWorker.Phase.SORTING,worker.status().phase(),"active sort reaches controlled source read");equal(peak,worker.reservedBytes(),"active scratch remains locally reserved");equal(peak,memory.stats().chargedBytes(),"active shared peak remains charged");
                    var before=worker.status();var rejected=owner.retain();throwsType(IllegalArgumentException.class,()->worker.submit(rejected,orderView(f)),"active plus pending local ceiling remains enforced");check(rejected.closed()&&!first.closed()&&worker.status()==before,"local rejection preserves active source/status");
                    worker.close();equal(WorldOrderWorker.Phase.CLOSED,worker.status().phase(),"nonblocking close publishes closed state");check(!first.closed(),"active source not closed before unwind");equal(peak,worker.reservedBytes(),"cancelled active scratch remains locally visible");equal(peak,memory.stats().chargedBytes(),"cancelled active shared charge survives close");
                }
                try { check(executor.awaitTermination(5,TimeUnit.SECONDS),"cancelled sorting worker terminates after source release"); }
                catch(InterruptedException cancelled) { Thread.currentThread().interrupt();throw new AssertionError("Sorting shutdown interrupted",cancelled); }
                check(first.closed()&&!owner.closed(),"active source releases independently after unwind");equal(0L,worker.reservedBytes(),"active local charge clears after unwind");equal(0L,memory.stats().chargedBytes(),"active shared charge clears after unwind");equal(0,memory.stats().accounts(),"active account finally released");
            }
        }
    }
    private static void emptyAndMarkers() {
        var snapshot=snapshot(1);var assets=assets(255);var layout=AtlasBuilder.empty(assets,BLOCK,LIVE);var raster=AtlasImages.capture(layout,0,0,LIVE);equal(0,raster.pages().size(),"empty atlas needs no dummy texture");
        var diagnostic=new ModelPlan.Problem(ModelPlan.Code.BUILTIN_MODEL,ClientAssetSnapshot.Kind.MODEL,MODEL,"","synthetic unsupported native visual");
        for(boolean marker:new boolean[]{false,true}) {
            var entry=marker?WorldModels.Entry.placeholder(snapshot.states().getFirst(),List.of(diagnostic)):WorldModels.Entry.empty(snapshot.states().getFirst());var models=WorldModels.capture(snapshot,layout,List.of(entry),new int[]{0},LIVE);var plan=WorldScenePlan.plan(models,WorldVisibility.keepAll(snapshot,layout),LIVE);
            try(var cache=new WorldSectionCache();var owner=WorldScene.build(plan,raster,cache,LIVE);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
                check(!owner.requiresPlaceholder()&&!index.requiresPlaceholder(),"complete diagnostic/empty scene");equal(0,owner.scene().orElseThrow().usage().faces(),"no fabricated block faces");var picked=index.query(ray(snapshot),LIVE);
                if(marker) { var hit=picked.hit().orElseThrow();check(hit.placeholder()&&hit.surface().isEmpty(),"diagnostic marker hit");equal(snapshot.states().getFirst(),hit.state(),"marker retains unsupported original state");equal(List.of(diagnostic),hit.visualProblems(),"exact visual diagnostic");near(2,hit.distance(),"analytic marker box"); }
                else check(picked.hit().isEmpty(),"explicit empty produces no pick");
            }
        }
    }
    private static void indexOwnership() {
        var f=fixture(2,255,false);try(var memory=new RenderMemory(new RenderMemory.Limits(4*1024*1024,4));var cache=new WorldSectionCache();var owner=build(f,cache)) {
            var input=owner.retain();var index=WorldPickIndex.prepare(input,WorldPickIndex.Limits.DEFAULT,memory,LIVE);check(!index.requiresPlaceholder()&&!input.closed(),"index owns consumed fresh scene wrapper");equal(owner.scene().orElseThrow().usage().knownPayloadBytes()+index.retainedBytes(),memory.stats().chargedBytes(),"index exact source plus retained arrays");
            cache.close();check(cache.stats().leases()>0,"cache close preserves scene pins");index.close();check(input.closed()&&index.released(),"index releases transferred source");equal(0L,memory.stats().chargedBytes(),"index admission released");owner.close();equal(0L,cache.stats().knownPayloadBytes(),"final cached source release");
        }
        try(var cache=new WorldSectionCache();var owner=build(f,cache)) { var input=owner.retain();try(var failed=WorldPickIndex.prepare(input,new WorldPickIndex.Limits(0,4*1024*1024),LIVE)) { check(failed.requiresPlaceholder()&&input.closed(),"failed index consumes only fresh owner");check(!owner.closed(),"independent original scene remains live"); } }
        try(var cache=new WorldSectionCache();var owner=build(f,cache)) { var input=owner.retain();throwsType(CancellationException.class,()->WorldPickIndex.prepare(input,()->true),"index cancellation separate");check(input.closed()&&!owner.closed(),"cancelled index releases only its consumed wrapper"); }
    }
    public static void main(String[] arguments) {
        if(arguments.length!=0)throw new IllegalArgumentException("No arguments supported");
        group("native snapshot identity and atomic failure",WorldCpuContracts::nativeSnapshot);
        group("model cube winding and provenance",WorldCpuContracts::geometry);
        group("atlas extrusion and exact binding",WorldCpuContracts::atlas);
        group("native visual assignment guards",WorldCpuContracts::assignments);
        group("explicit native visibility masks",WorldCpuContracts::visibility);
        group("complete world mesh metadata",WorldCpuContracts::meshReceipts);
        group("complete scene limits and identity",WorldCpuContracts::completeLimits);
        group("shared scene and cache ownership",WorldCpuContracts::cacheOwners);
        group("pinned cache replacement admission",WorldCpuContracts::pinnedReplacement);
        group("world geometry picking and alpha",WorldCpuContracts::picking);
        group("independent translucent centroid ordering",WorldCpuContracts::ordering);
        group("pending sort replacement admission",WorldCpuContracts::pendingOrderAdmission);
        group("ready sort replacement admission",WorldCpuContracts::readyOrderAdmission);
        group("transferred sort ownership and admission",WorldCpuContracts::transferredOrderAdmission);
        group("active sort cancellation and admission",WorldCpuContracts::activeOrderAdmission);
        group("explicit empty and unsupported markers",WorldCpuContracts::emptyAndMarkers);
        group("picking-index admission and source release",WorldCpuContracts::indexOwnership);
        System.out.println("PASS "+groups+" synthetic world contract groups; native/GPU parity is outside this harness.");
    }
}
