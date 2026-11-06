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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
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
    private static WorldSnapshot blendSnapshot(WorldSnapshot.Source source,WorldSnapshot.Extent extent,List<WorldSnapshot.Biome> biomes,int[] indices) {
        var result=WorldSnapshot.capture(source,extent,List.of(state(407)),biomes,new int[indices.length],indices,LIVE);
        check(!result.requiresPlaceholder(),"complete blend fixture");return result;
    }
    private static void blendFailure(WorldProblem.Code code,Runnable operation,String message) {
        try { operation.run();throw new AssertionError(message+": failure missing"); }
        catch(WorldCapture.ReadFailure failure) { equal(code,failure.problem().code(),message); }
    }
    private static void biomeBlending() {
        var source=snapshot(1).source();var second=new WorldSnapshot.Biome(911,"fixture:second",new AssetId("fixture","second"));
        var extent=new WorldSnapshot.Extent(-1,-64,-1,3,2,3);int[] indices=new int[18];
        for(int y=0;y<2;y++)for(int z=0;z<3;z++)for(int x=0;x<3;x++)indices[(y*3+z)*3+x]=x==2?1:0;
        var halo=blendSnapshot(source,extent,List.of(BIOME,second),indices);
        var target=blendSnapshot(source,new WorldSnapshot.Extent(0,-64,0,1,2,1),List.of(second,BIOME),new int[]{1,1});
        var blend=new WorldBiomeBlend(target,halo,new WorldBiomeBlend.Limits(1,18),LIVE);var seen=new HashSet<WorldSnapshot.Position>();
        var center=new WorldSnapshot.Position(0,-63,0);
        int result=blend.rgb(center,(biome,x,y,z)->{check(seen.add(new WorldSnapshot.Position(x,y,z)),"unique horizontal sample");equal(-63,y,"source Y is unchanged");return biome==0?0xff102030:0x00405060;});
        equal(0x203040,result,"floor RGB channels independently, ignore alpha");equal(9,seen.size(),"entire radius-one square");equal(9L,blend.samples(),"exact sample reservation");equal(halo,blend.source(),"borrowed exact halo");
        int positional=blend.rgb(center,(biome,x,y,z)->((int)(x+1)<<16)|((int)(z+1)<<8)|1);
        equal(0x010101,positional,"each neighbor receives its own grass-modifier coordinates");
        final int[] calls={0};blendFailure(WorldProblem.Code.LIMIT,()->blend.rgb(center,(biome,x,y,z)->{calls[0]++;return 0;}),"exhausted query allowance");equal(0,calls[0],"limit failure precedes callbacks");
        var shortBudget=new WorldBiomeBlend(target,halo,new WorldBiomeBlend.Limits(1,8),LIVE);
        blendFailure(WorldProblem.Code.LIMIT,()->shortBudget.rgb(center,(biome,x,y,z)->{calls[0]++;return 0;}),"whole footprint must fit");equal(0L,shortBudget.samples(),"rejected footprint consumes no samples");
        var unblended=new WorldBiomeBlend(target,target,WorldBiomeBlend.Limits.UNBLENDED,LIVE);
        equal(0xabcdef,unblended.rgb(center,(biome,x,y,z)->{equal(1,biome,"radius zero retains target palette ordering");return 0x80abcdef;}),"radius zero preserves RGB");equal(1L,unblended.samples(),"radius zero samples once");
        blendFailure(WorldProblem.Code.EXTENT,()->new WorldBiomeBlend(target,target,new WorldBiomeBlend.Limits(1,9),LIVE),"uncaptured halo is rejected");
        blendFailure(WorldProblem.Code.EXTENT,()->unblended.rgb(new WorldSnapshot.Position(0,-65,0),(biome,x,y,z)->{throw new AssertionError("missing height callback");}),"below-minimum plant biome is rejected");
        blendFailure(WorldProblem.Code.EXTENT,()->blend.rgb(new WorldSnapshot.Position(1,-63,0),(biome,x,y,z)->{throw new AssertionError("missing neighbor callback");}),"outside halo is not clamped");
        var otherSource=new WorldSnapshot.Source(UUID.randomUUID(),source.inputSha256(),source.backendBuild(),source.minecraftVersion(),source.dimension(),source.seed());
        var other=blendSnapshot(otherSource,extent,List.of(BIOME,second),indices);
        blendFailure(WorldProblem.Code.ASSIGNMENT,()->new WorldBiomeBlend(target,other,new WorldBiomeBlend.Limits(1,9),LIVE),"generation mismatch rejected");
        var conflict=indices.clone();conflict[4]=1;var inconsistent=blendSnapshot(source,extent,List.of(BIOME,second),conflict);
        blendFailure(WorldProblem.Code.PALETTE,()->new WorldBiomeBlend(target,inconsistent,new WorldBiomeBlend.Limits(1,9),LIVE),"overlapping biome disagreement rejected");
        var altered=new WorldSnapshot.Biome(BIOME.nativeBiomeId(),"fixture:alias",new AssetId("fixture","alias"));
        var wrongIdentity=blendSnapshot(source,extent,List.of(altered,second),indices);
        blendFailure(WorldProblem.Code.PALETTE,()->new WorldBiomeBlend(target,wrongIdentity,new WorldBiomeBlend.Limits(1,9),LIVE),"same numeric ID cannot hide a different native key");
        var wrongHeight=blendSnapshot(source,new WorldSnapshot.Extent(-1,-64,-1,3,1,3),List.of(BIOME),new int[9]);
        blendFailure(WorldProblem.Code.ASSIGNMENT,()->new WorldBiomeBlend(target,wrongHeight,new WorldBiomeBlend.Limits(1,9),LIVE),"full-height agreement required");
        var failed=WorldSnapshot.capture(source,extent,List.of(state(407)),List.of(BIOME),new int[0],new int[0],LIVE);
        blendFailure(WorldProblem.Code.PALETTE,()->new WorldBiomeBlend(target,failed,new WorldBiomeBlend.Limits(1,9),LIVE),"placeholder halo rejected");
        for(long origin:new long[]{Long.MIN_VALUE,Long.MAX_VALUE-3,9_007_199_254_740_993L}) {
            var distantHalo=blendSnapshot(source,new WorldSnapshot.Extent(origin,-64,origin,3,2,3),List.of(BIOME),new int[18]);
            var distantTarget=blendSnapshot(source,new WorldSnapshot.Extent(origin+1,-64,origin+1,1,2,1),List.of(BIOME),new int[2]);
            var distant=new WorldBiomeBlend(distantTarget,distantHalo,new WorldBiomeBlend.Limits(1,9),LIVE);
            equal(0x123456,distant.rgb(new WorldSnapshot.Position(origin+1,-63,origin+1),(biome,x,y,z)->0x123456),"large signed coordinates remain exact");
        }
        var edge=blendSnapshot(source,new WorldSnapshot.Extent(Long.MIN_VALUE,-64,0,1,2,1),List.of(BIOME),new int[2]);
        blendFailure(WorldProblem.Code.COORDINATE,()->new WorldBiomeBlend(edge,edge,new WorldBiomeBlend.Limits(1,9),LIVE),"expanded coordinate underflow rejected");
        var fresh=new WorldBiomeBlend(target,halo,new WorldBiomeBlend.Limits(1,9),LIVE);
        blendFailure(WorldProblem.Code.COORDINATE,()->fresh.rgb(new WorldSnapshot.Position(Long.MAX_VALUE,-63,0),(biome,x,y,z)->0),"sample coordinate overflow rejected");
        var boom=new AssertionError("sampler failure");try { fresh.rgb(center,(biome,x,y,z)->{throw boom;});throw new AssertionError("missing sampler failure"); }catch(AssertionError actual) { check(actual==boom,"sampler failure identity preserved"); }
        equal(9L,fresh.samples(),"failed query retains its full sample reservation");
        final boolean[] cancelled={false};var cancellable=new WorldBiomeBlend(target,halo,new WorldBiomeBlend.Limits(1,9),()->cancelled[0]);
        throwsType(CancellationException.class,()->cancellable.rgb(center,(biome,x,y,z)->{cancelled[0]=true;return 0;}),"in-flight cancellation escapes without partial color");equal(9L,cancellable.samples(),"cancelled query retains reservation");
        throwsType(CancellationException.class,()->new WorldBiomeBlend(target,halo,new WorldBiomeBlend.Limits(1,9),()->true),"cancelled setup exits before work");
        var widestHalo=blendSnapshot(source,new WorldSnapshot.Extent(-7,-64,-7,15,2,15),List.of(BIOME),new int[450]);
        var widest=new WorldBiomeBlend(target,widestHalo,new WorldBiomeBlend.Limits(7,225),LIVE);
        equal(0xffffff,widest.rgb(center,(biome,x,y,z)->0xffffffff),"maximum radius and channel sums stay bounded");equal(225L,widest.samples(),"maximum radius visits 225 neighbors");
        var budgeted=new WorldBiomeBlend(target,halo,new WorldBiomeBlend.Limits(1,9),LIVE);
        var tint=WorldTintInput.read(assets(255),target,"synthetic bounded blend",new WorldTintInput.Reader() {
            @Override public WorldTintInput.Layers colors(int cell) { return WorldTintInput.Layers.of(Map.of(0,budgeted.rgb(target.extent().position(cell),(biome,x,y,z)->0x123456))); }
        },WorldTintInput.Limits.DEFAULT,LIVE);
        check(tint.requiresPlaceholder()&&tint.problems().getFirst().code()==WorldProblem.Code.LIMIT,"later blend budget failure becomes structured tint diagnostic");equal(0,tint.tables(),"failed tint publishes no partial tables");equal(0L,tint.bytes(),"failed tint publishes no partial payload");
        var completeBlend=new WorldBiomeBlend(target,halo,new WorldBiomeBlend.Limits(1,18),LIVE);
        var completeTint=WorldTintInput.read(assets(255),target,"synthetic complete blend",new WorldTintInput.Reader() {
            @Override public WorldTintInput.Layers colors(int cell) { return WorldTintInput.Layers.of(Map.of(0,completeBlend.rgb(target.extent().position(cell),(biome,x,y,z)->y==-64?0xff0000:0x00ff00))); }
        },WorldTintInput.Limits.DEFAULT,LIVE);
        check(!completeTint.requiresPlaceholder(),"complete blended tint detaches through ordinary pipeline");equal(0xff0000,completeTint.rgb(0,0),"lower captured biome Y");equal(0x00ff00,completeTint.rgb(1,0),"upper captured biome Y");equal(target.id(),completeTint.snapshot().id(),"original native snapshot identity retained");equal(407,completeTint.snapshot().state(0).nativeStateId(),"original native state ID retained");
        throwsType(IllegalArgumentException.class,()->new WorldBiomeBlend.Limits(8,1),"radius hard bound");throwsType(IllegalArgumentException.class,()->new WorldBiomeBlend.Limits(1,0),"positive sample budget");
    }
    private static final AssetId WET_BLOCK=AssetId.minecraft("oak_slab"),WET_MODEL=AssetId.minecraft("block/synthetic_wet_base"),WET_SPRITE=AssetId.minecraft("block/synthetic_wet_pixel");
    private static ClientAssetSnapshot fluidAssets(Optional<AssetId> absent) {
        var values=new TreeMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset>();
        for(var kind:FluidGeometry.Kind.values())for(var sprite:kind.alphaSprites())if(!absent.equals(Optional.of(sprite)))add(values,ClientAssetSnapshot.Kind.TEXTURE,sprite,pixel(kind==FluidGeometry.Kind.WATER?160:255));
        add(values,ClientAssetSnapshot.Kind.TEXTURE,WET_SPRITE,pixel(255));
        add(values,ClientAssetSnapshot.Kind.BLOCKSTATE,WET_BLOCK,("{\"variants\":{\"\":{\"model\":\""+WET_MODEL+"\"}}}").getBytes(StandardCharsets.UTF_8));
        var faces=new StringJoiner(",");for(var direction:ModelPlan.Direction.values())faces.add("\""+direction.name().toLowerCase(Locale.ROOT)+"\":{\"texture\":\"#all\",\"tintindex\":0}");
        String model="{\"textures\":{\"all\":\""+WET_SPRITE+"\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,8,16],\"faces\":{"+faces+"}}]}";
        add(values,ClientAssetSnapshot.Kind.MODEL,WET_MODEL,model.getBytes(StandardCharsets.UTF_8));
        long bytes=values.values().stream().mapToLong(ClientAssetSnapshot.Asset::bytes).sum();
        return new ClientAssetSnapshot(new ClientAssetSnapshot.Receipt(Path.of("synthetic-fluid-fixture"),digest((model+absent).getBytes(StandardCharsets.UTF_8)),bytes,BlockModels.PROFILE,BlockModels.PROFILE,values.size(),0,bytes),values);
    }
    private static void fluidBlendMaterials() {
        var original=new RenderBlockState(407,"minecraft:oak_slab[type=bottom,waterlogged=true]",WET_BLOCK,Map.of("type","bottom","waterlogged","true"),Map.of("type",Set.of("bottom","top","double"),"waterlogged",Set.of("false","true")));
        var source=snapshot(1).source();var target=WorldSnapshot.capture(source,new WorldSnapshot.Extent(0,-64,0,1,1,1),List.of(original),List.of(BIOME),new int[1],new int[1],LIVE);
        var second=new WorldSnapshot.Biome(911,"fixture:water_second",new AssetId("fixture","water_second"));int[] indices={0,0,1,0,0,1,0,0,1};
        var halo=WorldSnapshot.capture(source,new WorldSnapshot.Extent(-1,-64,-1,3,1,3),List.of(original),List.of(BIOME,second),new int[9],indices,LIVE);
        var blend=new WorldBiomeBlend(target,halo,new WorldBiomeBlend.Limits(1,9),LIVE);
        int water=blend.rgb(target.extent().position(0),(at,x,y,z)->at==0?0x102030:0x405060);equal(0x203040,water,"native water palette channel average");
        String policy="native water color; horizontal RGB blend radius 1";var assets=fluidAssets(Optional.empty());
        int north=1<<ModelPlan.Direction.NORTH.ordinal();
        var cell=new FluidGeometry.Cell(FluidGeometry.Kind.WATER,0.9f,0.8f,0.7f,0.6f,63,north,true,true,0.15f,0.2f,water,0);
        var mesh=FluidGeometry.bake(assets,target,0,cell,policy,NativeGeometry.Limits.DEFAULT,LIVE);
        check(!mesh.requiresPlaceholder(),"complete blended water geometry: "+mesh.problems());equal(10,mesh.quads().size(),"reverse top/sides and one overlay");equal(FluidGeometry.packedBytes(cell),mesh.packet().orElseThrow().packedBytes(),"complete packed fluid allowance");
        equal(original,mesh.state(),"original waterlogged native state preserved");equal(target.id(),mesh.nativeSource().orElseThrow().snapshotId(),"original native snapshot preserved");
        for(var quad:mesh.quads()) {
            equal(water,quad.material().colorRgb(),"water RGB on every forward/reverse/overlay quad");equal(water,quad.material().multiplyRgb(0xffffff),"fluid uses independent white block tint");equal(-1,quad.tintIndex(),"fluid allocates no block tint layer");equal(BlockMesh.Placement.CELL,quad.placement(),"fluid ignores native model offsets");check(quad.nativeSource().orElseThrow().policy().contains(policy),"actual color policy retained in native provenance");
        }
        var prepared=TextureMaterials.prepare(assets,mesh,LIVE);check(!prepared.requiresPlaceholder(),"prepared blended fluid textures");
        var layout=AtlasBuilder.plan(List.of(prepared),AtlasBuilder.Limits.DEFAULT,LIVE);check(!layout.requiresPlaceholder(),"planned fluid atlas");var atlas=AtlasMeshes.bind(layout,prepared,LIVE);check(!atlas.requiresPlaceholder(),"bound fluid atlas");
        for(var face:atlas.faces()) { equal(TexturePreparation.AlphaClass.TRANSLUCENT,face.alpha(),"whole water source remains translucent");equal(water,face.source().material().colorRgb(),"atlas retains blended material RGB");check(face.source().nativeSource().orElseThrow().policy().contains(policy),"atlas retains color provenance"); }
        var basePlan=BlockModels.resolve(assets,original,LIVE);check(!basePlan.requiresPlaceholder(),"synthetic waterlogged JSON base resolves");var base=GeometryBaker.bake(basePlan,new int[]{0},LIVE);check(!base.requiresPlaceholder(),"synthetic JSON slab base bakes");
        var complete=NativeGeometry.combine(base,mesh,LIVE);check(!complete.requiresPlaceholder(),"complete base and fluid composition");equal(base.quads().size()+mesh.quads().size(),complete.quads().size(),"neither layer is dropped");
        for(int i=0;i<complete.quads().size();i++) { var quad=complete.quads().get(i);boolean model=i<base.quads().size();equal(model?0:-1,quad.tintIndex(),"independent base and fluid tint indices");equal(model?0xffffff:water,quad.material().colorRgb(),"independent base and fluid colors");equal(model?BlockMesh.Placement.MODEL:BlockMesh.Placement.CELL,quad.placement(),"independent model/fluid placement"); }
        var lava=new FluidGeometry.Cell(FluidGeometry.Kind.LAVA,1,1,1,1,63,0,false,false,0,0,0xffffff,15);var lavaMesh=FluidGeometry.bake(assets,target,0,lava,NativeGeometry.Limits.DEFAULT,LIVE);check(!lavaMesh.requiresPlaceholder(),"lava compatibility factory");
        for(var quad:lavaMesh.quads()) { equal(0xffffff,quad.material().colorRgb(),"lava remains white");equal(15,quad.lightEmission(),"lava emission retained");check(!quad.nativeSource().orElseThrow().policy().contains("water color"),"lava provenance makes no water-color claim"); }
        var supplied=FluidGeometry.bake(assets,target,0,cell,NativeGeometry.Limits.DEFAULT,LIVE);check(!supplied.requiresPlaceholder(),"supplied color compatibility factory");check(supplied.nativeSource().orElseThrow().policy().contains("supplied water material RGB")&&!supplied.nativeSource().orElseThrow().policy().contains("radius-zero"),"low-level RGB input does not invent a sampling radius");
        var missing=FluidGeometry.bake(fluidAssets(Optional.of(FluidGeometry.Kind.WATER.flow())),target,0,cell,policy,NativeGeometry.Limits.DEFAULT,LIVE);
        check(missing.requiresPlaceholder()&&missing.quads().isEmpty()&&missing.packet().isEmpty(),"missing dependency publishes no partial colored mesh");
        long bytes=FluidGeometry.packedBytes(cell);var exact=new NativeGeometry.Limits(10,0,bytes,bytes,8);
        check(!FluidGeometry.bake(assets,target,0,cell,policy,exact,LIVE).requiresPlaceholder(),"exact packed color geometry budget");
        var shortBytes=new NativeGeometry.Limits(10,0,bytes-1,bytes,8);var rejected=FluidGeometry.bake(assets,target,0,cell,policy,shortBytes,LIVE);check(rejected.requiresPlaceholder()&&rejected.quads().isEmpty(),"one-byte deficit withholds whole fluid geometry");
        throwsType(IllegalArgumentException.class,()->FluidGeometry.bake(assets,target,0,cell," ",NativeGeometry.Limits.DEFAULT,LIVE),"blank color policy rejected");throwsType(IllegalArgumentException.class,()->FluidGeometry.bake(assets,target,0,cell,"x".repeat(129),NativeGeometry.Limits.DEFAULT,LIVE),"color policy bound");
        throwsType(CancellationException.class,()->FluidGeometry.bake(assets,target,0,cell,policy,NativeGeometry.Limits.DEFAULT,()->true),"fluid bake cancellation escapes");
    }
    private static WorldVisualReport.Limits reportLimits(long cells,int issues,int references,int examples,long visits,long text,long payload,long working) { return new WorldVisualReport.Limits(cells,issues,references,examples,visits,text,payload,working); }
    private static void visualReports() {
        var f=fixture(1,255,false);var primary=new ModelPlan.Problem(ModelPlan.Code.MISSING_ASSET,ClientAssetSnapshot.Kind.MODEL,MODEL,"/parent","Missing exact parent");var secondary=new ModelPlan.Problem(ModelPlan.Code.UNSUPPORTED_GEOMETRY,ClientAssetSnapshot.Kind.BLOCKSTATE,BLOCK,"/native","Unsupported native visual");var unused=new ModelPlan.Problem(ModelPlan.Code.FORMAT,ClientAssetSnapshot.Kind.MODEL,MODEL,"/unused","Unassigned palette entry");
        var snapshot=WorldSnapshot.capture(f.snapshot().source(),new WorldSnapshot.Extent(f.snapshot().extent().minX(),-64,f.snapshot().extent().minZ(),8,1,1),List.of(state(407),state(915)),List.of(BIOME),new int[]{0,0,0,0,0,1,1,0},new int[8],LIVE);
        var entries=List.of(WorldModels.Entry.from(f.prototype()),WorldModels.Entry.empty(state(407)),WorldModels.Entry.placeholder(state(407),List.of(primary,primary,secondary)),WorldModels.Entry.placeholder(state(407),List.of(primary)),WorldModels.Entry.placeholder(state(915),List.of(primary)),WorldModels.Entry.empty(state(915)),WorldModels.Entry.placeholder(state(407),List.of(unused)));
        int[] assignments={0,1,2,3,2,4,5,0};var models=WorldModels.capture(snapshot,f.layout(),entries,assignments,LIVE);check(!models.requiresPlaceholder(),"complete visual report assignments");
        var visibility=WorldVisibility.keepAll(snapshot,f.layout());var plan=WorldScenePlan.plan(models,visibility,LIVE);check(!plan.requiresPlaceholder(),"complete diagnostic scene plan");WorldVisualReport detached;
        try(var cache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT);var owner=WorldScene.build(plan,f.raster(),cache,LIVE)) {
            check(!owner.requiresPlaceholder(),"complete report scene");var scene=owner.scene().orElseThrow();var report=WorldVisualReport.capture(scene,LIVE);check(!report.requiresPlaceholder(),"complete visual report: "+report.problems());check(report.matches(scene),"report matches exact immutable assignments");detached=report;
            var usage=report.usage().orElseThrow();equal(8L,usage.capturedCells(),"complete source coverage");equal(2L,usage.meshCells(),"mesh assignments include all source cells");equal(2L,usage.emptyCells(),"explicit empty assignments");equal(4L,usage.placeholderCells(),"distinct affected cells, not summed diagnostic counts");equal(3,report.issues().size(),"same diagnostic/state coalesces across entries; unused entry omitted");equal(5,usage.references(),"used raw problem references stay bounded, including duplicates");
            var first=report.issues().get(0);equal(407,first.nativeStateId(),"canonical native ID order");equal(state(407).original(),first.originalState(),"original native state string retained");equal(primary,first.problem(),"exact diagnostic text and source retained");equal(3L,first.affectedCells(),"duplicate problem does not double-count cells");equal(snapshot.extent().position(2),first.first(),"exact distant first coordinate");equal(snapshot.extent().position(4),first.last(),"exact distant last coordinate");equal(List.of(snapshot.extent().position(2),snapshot.extent().position(3),snapshot.extent().position(4)),first.examples(),"earliest examples follow source cell order");equal(2L,report.issues().get(1).affectedCells(),"second diagnostic overlap is independent");equal(915,report.issues().get(2).nativeStateId(),"same text on another native ID is a separate issue");
            throwsType(UnsupportedOperationException.class,()->report.issues().clear(),"immutable report rows");throwsType(UnsupportedOperationException.class,()->first.examples().clear(),"immutable detached coordinates");
            var exact=reportLimits(8,3,5,4,usage.visits(),usage.textBytes(),usage.payloadBytes(),512L*1024*1024);var exactReport=WorldVisualReport.capture(scene,exact,LIVE);check(!exactReport.requiresPlaceholder(),"exact report count/text/payload/visit boundaries");equal(report.issues(),exactReport.issues(),"bounded report preserves complete rows");long peak=WorldVisualReport.workingBytes(scene,exact);
            var limits=List.of(reportLimits(7,3,5,4,usage.visits(),usage.textBytes(),usage.payloadBytes(),512L*1024*1024),reportLimits(8,2,5,4,usage.visits(),usage.textBytes(),usage.payloadBytes(),512L*1024*1024),reportLimits(8,3,4,4,usage.visits(),usage.textBytes(),usage.payloadBytes(),512L*1024*1024),reportLimits(8,3,5,4,usage.visits()-1,usage.textBytes(),usage.payloadBytes(),512L*1024*1024),reportLimits(8,3,5,4,usage.visits(),usage.textBytes()-1,usage.payloadBytes(),512L*1024*1024),reportLimits(8,3,5,4,usage.visits(),usage.textBytes(),usage.payloadBytes()-1,512L*1024*1024),reportLimits(8,3,5,4,usage.visits(),usage.textBytes(),usage.payloadBytes(),peak-1));
            for(var limit:limits) { var failed=WorldVisualReport.capture(scene,limit,LIVE);check(failed.requiresPlaceholder()&&failed.problems().getFirst().code()==WorldProblem.Code.LIMIT,"report limit is explicit");check(failed.issues().isEmpty()&&failed.usage().isEmpty()&&failed.bytes()==0,"limit failure publishes no partial counts/rows");check(!failed.matches(scene),"failed report cannot satisfy a source match"); }
            var noExamples=WorldVisualReport.capture(scene,reportLimits(8,3,5,0,1000,4096,4096,512L*1024*1024),LIVE);check(!noExamples.requiresPlaceholder(),"examples can be disabled");for(var issue:noExamples.issues())check(issue.examples().isEmpty(),"no hidden example truncation");equal(first.first(),noExamples.issues().get(0).first(),"first coordinate independent of sample cap");
            var oneExample=WorldVisualReport.capture(scene,reportLimits(8,3,5,1,1000,4096,4096,512L*1024*1024),LIVE);check(!oneExample.requiresPlaceholder(),"one-example report");equal(List.of(snapshot.extent().position(2)),oneExample.issues().get(0).examples(),"one earliest bounded example");equal(3L,oneExample.issues().get(0).affectedCells(),"sample cap never truncates counts");
            var replacement=WorldModels.capture(snapshot,f.layout(),entries,assignments,LIVE);check(!replacement.id().equals(models.id()),"new assignment identity despite identical native data");
            try(var otherCache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT);var otherOwner=WorldScene.build(WorldScenePlan.plan(replacement,visibility,LIVE),f.raster(),otherCache,LIVE)) { check(!otherOwner.requiresPlaceholder(),"same-receipt replacement scene");check(!report.matches(otherOwner.scene().orElseThrow()),"native/assets equality cannot reuse another assignment report"); }
            var reorderedEntries=new ArrayList<>(entries);Collections.reverse(reorderedEntries);int[] reversed=Arrays.stream(assignments).map(index->entries.size()-1-index).toArray();var reordered=WorldModels.capture(snapshot,f.layout(),reorderedEntries,reversed,LIVE);
            try(var otherCache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT);var otherOwner=WorldScene.build(WorldScenePlan.plan(reordered,visibility,LIVE),f.raster(),otherCache,LIVE)) { check(!otherOwner.requiresPlaceholder(),"reordered visual palette");var stable=WorldVisualReport.capture(otherOwner.scene().orElseThrow(),LIVE);check(!stable.requiresPlaceholder(),"reordered report");equal(report.issues(),stable.issues(),"canonical rows and earliest examples ignore palette order"); }
            long leases=cache.stats().leases();try(var memory=new RenderMemory(new RenderMemory.Limits(peak,1))) {
                var held=WorldVisualReport.captureOwned(scene,exact,memory,LIVE);equal(report.bytes(),memory.stats().chargedBytes(),"owned admission shrinks to detached report only");equal(leases,cache.stats().leases(),"report adds no source scene lease");var retained=held.retain();held.close();equal(report.bytes(),memory.stats().chargedBytes(),"independent report owner retains charge");owner.close();equal(0L,cache.stats().leases(),"source can release while report stays owned");equal(first,retained.value().issues().get(0),"report remains readable after source release");retained.close();equal(0L,memory.stats().chargedBytes(),"final report owner releases admission");equal(0,memory.stats().accounts(),"no report account leak");
            }
        }
        equal(3,detached.issues().size(),"detached report survives source/cache shutdown");
        try(var cache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT);var owner=build(f,cache)) {
            var scene=owner.scene().orElseThrow();var defaults=WorldVisualReport.Limits.DEFAULT;var empty=WorldVisualReport.capture(scene,reportLimits(1,0,0,0,1,4096,4096,512L*1024*1024),LIVE);check(!empty.requiresPlaceholder()&&empty.issues().isEmpty(),"all-mesh report needs no diagnostic budget");equal(1L,empty.usage().orElseThrow().meshCells(),"all-mesh source coverage");
            long peak=WorldVisualReport.workingBytes(scene,defaults);try(var memory=new RenderMemory(new RenderMemory.Limits(peak-1,1))) { throwsType(RenderMemory.LimitException.class,()->WorldVisualReport.captureOwned(scene,defaults,memory,LIVE),"shared report peak rejection");equal(0L,memory.stats().chargedBytes(),"rejected peak leaves no account"); }
            try(var memory=new RenderMemory(new RenderMemory.Limits(peak,1))) {
                var shortVisit=reportLimits(1,0,0,0,0,4096,4096,512L*1024*1024);throwsType(WorldCapture.ReadFailure.class,()->WorldVisualReport.captureOwned(scene,shortVisit,memory,LIVE),"owned report failure escapes structured diagnostic");equal(0L,memory.stats().chargedBytes(),"failed owned report releases peak");final int[] checks={0};throwsType(CancellationException.class,()->WorldVisualReport.captureOwned(scene,defaults,memory,()->++checks[0]>2),"cancellation during admitted report");equal(0L,memory.stats().chargedBytes(),"cancelled report releases peak");equal(0,memory.stats().accounts(),"no failed/cancelled report account leak");
            }
            throwsType(CancellationException.class,()->WorldVisualReport.capture(scene,()->true),"cancelled report preflight");throwsType(IllegalArgumentException.class,()->reportLimits(1,0,0,17,1,0,0,0),"example count hard bound");
        }
    }
    private static final class ReportEntriesFault implements AutoCloseable {
        final WorldModels models;final List<WorldModels.Entry> original;final java.lang.reflect.Field field;boolean fired;
        ReportEntriesFault(WorldModels models,Runnable before,Error failure) {
            this.models=models;original=models.entries();
            try { field=WorldModels.class.getDeclaredField("entries");field.setAccessible(true);field.set(models,new AbstractList<WorldModels.Entry>() {
                @Override public int size() { return original.size(); }
                @Override public WorldModels.Entry get(int index) { if(!fired) { fired=true;before.run();throw failure; }return original.get(index); }
            }); }catch(ReflectiveOperationException error) { throw new AssertionError("controlled report traversal fault unavailable",error); }
        }
        @Override public void close() { try { field.set(models,original); }catch(ReflectiveOperationException error) { throw new AssertionError("report traversal restore failed",error); } }
    }
    private static void reportWorkers() {
        var f=fixture(2,255,false);var limits=WorldVisualReport.Limits.DEFAULT;
        try(var cache=new WorldSectionCache();var source=build(f,cache)) {
            var scene=source.scene().orElseThrow();long peak=WorldVisualReport.workingBytes(scene,limits);var receipt=WorldVisualReport.receipt(scene);
            for(boolean ready:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(peak,1));var executor=new DrainExecutor();var worker=new WorldVisualReportWorker(peak,memory,()->executor)) {
                var first=source.retain();long firstRevision=worker.submit(first,limits);equal(peak,worker.reservedBytes(),"queued report reserves full source/output/scratch peak");equal(peak,memory.stats().chargedBytes(),"shared admission precedes traversal");var queued=worker.status();
                throwsType(IllegalArgumentException.class,()->worker.submit(first,limits),"same worker-owned wrapper rejected");check(!first.closed(),"same-wrapper misuse preserves ownership");equal(queued,worker.status(),"same-wrapper rejection preserves status");
                var rejected=source.retain();throwsType(NullPointerException.class,()->worker.submit(rejected,null),"invalid report limits");check(rejected.closed(),"fresh rejected report wrapper consumed");equal(queued,worker.status(),"invalid replacement preserves pending report");
                if(ready) { executor.runNext();check(first.closed(),"READY report releases its source wrapper");check(worker.status().phase()==WorldVisualReportWorker.Phase.READY,"worker report READY"); }
                var replacement=source.retain();long revision=worker.submit(replacement,limits);equal(firstRevision+1,revision,"pending/READY replacement advances one revision");check(first.closed(),"replacement consumes obsolete source");equal(peak,memory.stats().chargedBytes(),"idle pending/READY charge supplies atomic replacement credit");executor.runNext();check(replacement.closed(),"completed report has no source wrapper ownership");
                check(worker.take(firstRevision).isEmpty(),"obsolete report revision withheld");var wrong=new WorldVisualReport.Receipt(receipt.source(),receipt.snapshotId(),UUID.randomUUID(),receipt.extent(),receipt.archiveSha256(),receipt.version(),receipt.expectedVersion());check(worker.take(revision,wrong).isEmpty(),"mismatched assignment receipt does not consume READY");
                var completion=worker.take(revision,receipt).orElseThrow();check(completion.report().matches(scene),"delivered report retains exact source receipt");check(worker.take(revision).isEmpty(),"report transfers exactly once");equal(0L,worker.reservedBytes(),"delivered report is external to worker reservation");equal(completion.report().bytes(),memory.stats().chargedBytes(),"detached retained payload replaces preparation peak after unwind");
                var blocked=source.retain();var delivered=worker.status();throwsType(RenderMemory.LimitException.class,()->worker.submit(blocked,limits),"delivered owner cannot provide replacement credit");check(blocked.closed(),"shared rejection consumes fresh source only");equal(delivered,worker.status(),"shared rejection preserves delivered status/revision");worker.invalidate();worker.close();check(!completion.closed(),"delivered report survives invalidation and shutdown");equal(2L,completion.report().usage().orElseThrow().capturedCells(),"detached report remains readable");completion.close();noWorkerCharge(memory);
            }
            for(int kind=0;kind<3;kind++)try(var memory=new RenderMemory(new RenderMemory.Limits(peak,1));var executor=new StartExecutor(kind);var worker=new WorldVisualReportWorker(peak,memory,()->executor)) {
                var rejected=source.retain();if(kind==2)check(caughtFailure(()->worker.submit(rejected,limits))==executor.failure,"startup Error identity preserved");else equal(1L,worker.submit(rejected,limits),"ordinary startup failure returns FAILED token");
                check(rejected.closed(),"failed dispatch releases scene wrapper");equal(WorldVisualReportWorker.Phase.FAILED,worker.status().phase(),"failed report startup status");noWorkerCharge(memory);long revision=worker.submit(source.retain(),limits);equal(2L,revision,"report retry after startup failure");executor.queued.remove(0).run();worker.take(revision,receipt).orElseThrow().close();noWorkerCharge(memory);
            }
            for(boolean error:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(peak,1));var executor=new ShutdownExecutor(error);var worker=new WorldVisualReportWorker(peak,memory,()->executor)) {
                var pending=source.retain();worker.submit(pending,limits);firstShutdownFails(worker,executor);check(pending.closed(),"failed shutdown still releases pending report source");noWorkerCharge(memory);var rejected=source.retain();throwsType(IllegalStateException.class,()->worker.submit(rejected,limits),"report shutdown seals input before retry");check(rejected.closed(),"closed-worker rejection consumes source");retryShutdown(worker,executor);executor.runAll();equal(WorldVisualReportWorker.Phase.CLOSED,worker.status().phase(),"report shutdown retry remains terminal");
            }
            for(boolean tracked:new boolean[]{false,true})for(int mode=0;mode<4;mode++)try(var memory=new RenderMemory(new RenderMemory.Limits(peak*2,2));var executor=new DrainExecutor();var worker=new WorldVisualReportWorker(mode==3?peak:peak*2,tracked?memory:null,()->executor)) {
                int selected=mode;var failure=new AssertionError("report traversal failure");final long[] latest={0};var first=source.retain();worker.submit(first,limits);
                try(var fault=new ReportEntriesFault(scene.plan().models(),()->{
                    equal(peak,worker.reservedBytes(),"active report retains its full execution peak");
                    if(selected==0)latest[0]=worker.invalidate();
                    else if(selected==3) { var fresh=source.retain();var status=worker.status();throwsType(IllegalStateException.class,()->worker.submit(fresh,limits),"active peak blocks over-bound pending report");check(fresh.closed()&&!first.closed(),"local rejection preserves active scene ownership");equal(status,worker.status(),"active local rejection preserves status"); }
                    else { latest[0]=worker.submit(source.retain(),limits);if(selected==2)executor.nextFailure=new AssertionError("report redispatch failure"); }
                },failure)) {
                    check(caughtFailure(executor::runNext)==failure,"active report traversal Error stays primary");check(fault.fired&&first.closed(),"active failure releases source before drain recovery");
                    if(selected==1) { equal(WorldVisualReportWorker.Phase.QUEUED,worker.status().phase(),"accepted latest report survives older drain failure");equal(peak,worker.reservedBytes(),"only latest report peak remains after unwind");executor.runNext();worker.take(latest[0],receipt).orElseThrow().close(); }
                    else { check(worker.take(worker.status().revision()).isEmpty(),"failed/cancelled report never publishes partial output");if(selected==0)equal(WorldVisualReportWorker.Phase.WAITING,worker.status().phase(),"older failure cannot replace invalidated status");else equal(WorldVisualReportWorker.Phase.FAILED,worker.status().phase(),"current/redispatch failure remains retryable");noWorkerCharge(memory);long retry=worker.submit(source.retain(),limits);executor.runNext();worker.take(retry,receipt).orElseThrow().close(); }
                }
                equal(0L,worker.reservedBytes(),"report recovery releases worker charge");noWorkerCharge(memory);
            }
            try(var memory=new RenderMemory(new RenderMemory.Limits(peak,1));var executor=new DrainExecutor();var worker=new WorldVisualReportWorker(peak,memory,()->executor)) {
                var failedLimits=reportLimits(2,0,0,0,0,4096,4096,512L*1024*1024);long revision=worker.submit(source.retain(),failedLimits);executor.runNext();equal(WorldProblem.Code.LIMIT,worker.status().problems().getFirst().code(),"structured report failure retains its diagnostic code");check(worker.take(revision).isEmpty(),"failed census has no completion");noWorkerCharge(memory);
            }
        }
        try(var cache=new WorldSectionCache();var memory=new RenderMemory(new RenderMemory.Limits(32L*1024*1024,1));var executor=new DrainExecutor();var worker=new WorldVisualReportWorker(32L*1024*1024,memory,()->executor)) {
            var original=build(f,cache);var failure=new AssertionError("report source release failure");long revision=worker.submit(original,limits);
            try(var fault=new TicketFault(original,failure)) { check(caughtFailure(executor::runNext)==failure,"failed source release prevents READY publication");fault.allVisited();check(original.closed()&&worker.take(revision).isEmpty(),"unreturned report discarded after source cleanup failure");noWorkerCharge(memory); }
            long retry=worker.submit(build(f,cache),limits);executor.runNext();var completion=worker.take(retry).orElseThrow();worker.close();cache.close();equal(2L,completion.report().usage().orElseThrow().meshCells(),"completion survives final source/cache/worker shutdown");completion.close();noWorkerCharge(memory);
        }
    }
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
    private static void interruptedSceneBundles() {
        var f=fixture(4,255,false);
        for(Throwable injected:new Throwable[]{new CancellationException("scene bundle cancellation"),new IllegalStateException("scene bundle failure"),new AssertionError("scene bundle error")}) {
            try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var cache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT,memory.open("interrupted scene cache"))) {
                BooleanSupplier interrupted=()->{
                    if(cache.stats().leases()>=2) { if(injected instanceof RuntimeException runtime)throw runtime;throw (Error)injected; }
                    return false;
                };
                try { var unexpected=WorldScene.build(f.plan(),f.raster(),cache,interrupted);unexpected.close();throw new AssertionError("interrupted build returned a result"); }
                catch(RuntimeException|Error failure) { check(failure==injected,"original build interruption remains primary"); }
                equal(0L,cache.stats().leases(),"complete and incomplete tickets both released");equal(0,cache.stats().reservations(),"interrupted reservation removed");equal(1,cache.stats().sections(),"only first completed section stays reusable");
                try(var owner=build(f,cache)) { equal(4L,cache.stats().leases(),"complete rebuild owns exactly four tickets");equal(4,owner.scene().orElseThrow().sections().size(),"rebuild publishes full source coverage"); }
                equal(0L,cache.stats().leases(),"rebuild owner releases complete bundle");cache.close();equal(0L,cache.stats().knownPayloadBytes(),"closed cache clears interrupted source references");equal(0L,memory.stats().chargedBytes(),"final cache release clears shared charge");equal(0,memory.stats().accounts(),"no interrupted cache account leak");
            }
        }
    }
    private static void cacheOwners() {
        cacheRetirementFailures();
        interruptedSceneBundles();
        var f=fixture(2,255,false);try(var cache=new WorldSectionCache()) {
            var first=build(f,cache);var retained=first.retain();var second=build(f,cache);var scene=first.scene().orElseThrow();check(scene.sections().getFirst()==second.scene().orElseThrow().sections().getFirst(),"same epoch shares cached mesh");equal(4L,cache.stats().leases(),"two builds own four section tickets");
            first.close();equal(4L,cache.stats().leases(),"retained scene shares existing ticket owner");second.close();equal(2L,cache.stats().leases(),"second build releases its tickets");check(!cache.clear(),"pinned clear rejected");retained.close();equal(0L,cache.stats().leases(),"last scene releases tickets");check(cache.clear(),"unleased cache clear");equal(0L,cache.stats().knownPayloadBytes(),"clear releases source/mesh demand");
        }
    }
    /** Journaling fault commits insertion/removal before throwing; the real cache counters must remain unchanged. */
    private static final class CacheInsertionFault implements AutoCloseable {
        final WorldSectionCache cache;final java.lang.reflect.Field field;final LinkedHashMap<Object,Object> original;final Throwable primary,rollback;
        @SuppressWarnings("unchecked") CacheInsertionFault(WorldSectionCache cache,Throwable primary,Throwable rollback) {
            this.cache=cache;this.primary=primary;this.rollback=rollback;
            try {
                field=WorldSectionCache.class.getDeclaredField("entries");field.setAccessible(true);original=(LinkedHashMap<Object,Object>)field.get(cache);
                field.set(cache,new LinkedHashMap<Object,Object>() {
                    @Override public Object get(Object key) { return original.get(key); }
                    @Override public int size() { return original.size(); }
                    @Override public Collection<Object> values() { return original.values(); }
                    @Override public Object put(Object key,Object value) { original.put(key,value);if(CacheInsertionFault.this.primary instanceof Error error)throw error;throw (RuntimeException)CacheInsertionFault.this.primary; }
                    @Override public Object remove(Object key) { var removed=original.remove(key);if(CacheInsertionFault.this.rollback instanceof Error error)throw error;if(CacheInsertionFault.this.rollback instanceof RuntimeException failure)throw failure;return removed; }
                });
            }catch(ReflectiveOperationException failure) { throw new AssertionError("controlled cache insertion fault unavailable",failure); }
        }
        @Override public void close() {
            try { field.set(cache,original); }catch(ReflectiveOperationException failure) { throw new AssertionError("cache insertion fault restore failed",failure); }
        }
    }
    private static void cacheReservationFailures() {
        var f=fixture(2,255,false);
        for(int kind=0;kind<3;kind++)for(boolean cleanupError:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var account=memory.open("cache insertion fixture");var cache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT,account)) {
            cache.prepare(f.plan(),LIVE);var firstPlan=f.plan().sections().getFirst();var nextPlan=f.plan().sections().get(1);
            try(var first=cache.reserve(firstPlan,f.plan().limits().workingBytes())) {
                first.complete(WorldMesher.assemble(firstPlan,LIVE));var pinned=first.mesh();var before=cache.stats();var admitted=memory.stats();
                Throwable primary=switch(kind) { case 0->new IllegalStateException("injected cache journal rejection");case 1->new OutOfMemoryError("injected cache journal OOM");default->new AssertionError("injected cache journal error"); };Throwable rollback=cleanupError?new AssertionError("injected post-remove cache rollback error"):null;
                try(var fault=new CacheInsertionFault(cache,primary,rollback)) {
                    var actual=caughtFailure(()->cache.reserve(nextPlan,f.plan().limits().workingBytes()));check(actual==primary,"cache journal preserves exact insertion failure");equal(cleanupError?1:0,primary.getSuppressed().length,"cache rollback suppression count");if(cleanupError)check(primary.getSuppressed()[0]==rollback,"cache rollback error suppressed onto insertion failure");equal(before,cache.stats(),"failed insertion creates no orphan slot or committed lease/payload counters");equal(admitted,memory.stats(),"failed insertion preserves complete shared cache admission");check(first.cached()&&first.mesh()==pinned,"failed insertion preserves pinned cached output");
                }
                try(var retry=cache.reserve(nextPlan,f.plan().limits().workingBytes())) { check(!retry.cached(),"failed journal leaves no incomplete reservation blocking retry");retry.complete(WorldMesher.assemble(nextPlan,LIVE));equal(f.snapshot().extent().position(1),retry.mesh().positionForFace(0),"retry retains exact original native position");equal(state(407),retry.mesh().stateForFace(0),"retry retains original native state");equal(2L,cache.stats().leases(),"successful retry commits exactly one new lease"); }
                equal(1L,cache.stats().leases(),"retry close preserves independent pinned first ticket");
            }
            cache.close();noWorkerCharge(memory);
        }
    }
    /** Single-shot enumeration fault; successful retirement may replace the private registry. */
    private static final class CacheRetirementFault implements AutoCloseable {
        final WorldSectionCache cache;final java.lang.reflect.Field field;final LinkedHashMap<Object,Object> original,installed;final Error failure;boolean fired;int removals;
        @SuppressWarnings("unchecked") CacheRetirementFault(WorldSectionCache cache,Error failure) {
            this.cache=cache;this.failure=failure;
            try {
                field=WorldSectionCache.class.getDeclaredField("entries");field.setAccessible(true);original=(LinkedHashMap<Object,Object>)field.get(cache);
                installed=new LinkedHashMap<Object,Object>() {
                    @Override public Object get(Object key) { return original.get(key); }
                    @Override public Object put(Object key,Object value) { return original.put(key,value); }
                    @Override public int size() { return original.size(); }
                    @Override public Object remove(Object key) { removals++;return original.remove(key); }
                    @Override public Collection<Object> values() { return new AbstractCollection<>() {
                        @Override public int size() { return original.size(); }
                        @Override public Iterator<Object> iterator() { var iterator=original.values().iterator();return new Iterator<>() {
                            @Override public boolean hasNext() { return iterator.hasNext(); }
                            @Override public Object next() { var value=iterator.next();if(!fired) { fired=true;throw CacheRetirementFault.this.failure; }return value; }
                            @Override public void remove() { removals++;iterator.remove(); }
                        }; }
                    }; }
                };field.set(cache,installed);
            }catch(ReflectiveOperationException error) { throw new AssertionError("controlled cache retirement fault unavailable",error); }
        }
        @Override public void close() { try { if(field.get(cache)==installed)field.set(cache,original); }catch(ReflectiveOperationException error) { throw new AssertionError("cache retirement fault restore failed",error); } }
    }
    private static void cacheRetirementFailures() {
        var f=fixture(4,128,false);
        for(boolean seal:new boolean[]{false,true})for(boolean oom:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var account=memory.open("cache retirement fixture");var cache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT,account)) {
            cache.prepare(f.plan());var firstPlan=f.plan().sections().get(0);var secondPlan=f.plan().sections().get(1);var idlePlan=f.plan().sections().get(2);
            try(var first=cache.reserve(firstPlan,f.plan().limits().workingBytes());var second=cache.reserve(secondPlan,f.plan().limits().workingBytes())) {
                first.complete(WorldMesher.assemble(firstPlan,LIVE));var mesh=first.mesh();try(var idle=cache.reserve(idlePlan,f.plan().limits().workingBytes())) { idle.complete(WorldMesher.assemble(idlePlan,LIVE)); }
                var before=cache.stats();var shared=memory.stats();var admitted=account.stats();Error primary=oom?new OutOfMemoryError("injected retirement enumeration OOM"):new AssertionError("injected retirement enumeration error");Runnable retire=seal?cache::close:cache::retireUnleased;
                try(var fault=new CacheRetirementFault(cache,primary)) {
                    check(caughtFailure(retire)==primary,"retirement preparation preserves exact enumeration error");check(fault.fired&&fault.removals==0,"failed preparation never removes old entries");equal(before,cache.stats(),"retirement preparation leaves cache flags/counters intact");equal(shared,memory.stats(),"retirement preparation preserves actual and future shared charge");equal(admitted,account.stats(),"failed preparation preserves account state");
                    try(var probe=cache.reserve(firstPlan,f.plan().limits().workingBytes())) { check(probe.cached()&&probe.mesh()==mesh,"failed preparation leaves prepared admission and pinned mesh usable"); }equal(before,cache.stats(),"temporary cached lease probe restores exact counters");
                    retire.run();equal(0,fault.removals,"retirement commits a private registry without old-entry removal callbacks");
                }
                var kept=cache.stats();equal(seal,kept.closed(),"only shutdown seals cache admission");equal(2,kept.sections(),"retirement keeps only pinned complete/incomplete slots");equal(2,kept.leasedSections(),"both pinned slots remain live");equal(1,kept.reservations(),"incomplete ticket retains its admitted output");equal(2L,kept.leases(),"retirement preserves exact live ticket count");equal(1,kept.epochs(),"pinned source epoch survives");equal(before.sourcePayloadBytes(),kept.sourcePayloadBytes(),"shared native source remains charged once");
                long expectedMesh=firstPlan.usage().orElseThrow().outputBytes()+secondPlan.usage().orElseThrow().outputBytes();equal(expectedMesh,kept.meshBytes(),"retained mesh counters follow actual pinned slots");equal(kept.knownPayloadBytes(),memory.stats().chargedBytes(),"idle mesh and unallocated future output release their charge");check(memory.stats().chargedBytes()<shared.chargedBytes(),"retirement removes old idle/planned demand");equal(seal,account.stats().retiring(),"shutdown seals account without releasing pinned payload");check(first.mesh()==mesh,"pinned mesh identity survives registry replacement");equal(state(407),first.mesh().stateForFace(0),"retirement preserves original native state");
                second.complete(WorldMesher.assemble(secondPlan,LIVE));equal(f.snapshot().extent().position(1),second.mesh().positionForFace(0),"already-admitted incomplete ticket can finish after retirement");equal(0,cache.stats().reservations(),"completion updates the retained slot");
                if(seal)throwsType(IllegalStateException.class,()->cache.reserve(firstPlan,f.plan().limits().workingBytes()),"closed cache rejects new leases");else throwsType(WorldProblem.Failure.class,()->cache.reserve(firstPlan,f.plan().limits().workingBytes()),"retired future admission cannot be reused without prepare");
            }
            cache.close();equal(0L,cache.stats().knownPayloadBytes(),"last ticket release and close clear retired payload");noWorkerCharge(memory);
        }
        for(boolean lastTicket:new boolean[]{false,true})for(boolean same:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var account=memory.open("retirement publication fixture");var cache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT,account)) {
            WorldSectionCache.Ticket ticket;
            if(lastTicket) { cache.prepare(f.plan());var plan=f.plan().sections().getFirst();ticket=cache.reserve(plan,f.plan().limits().workingBytes());ticket.complete(WorldMesher.assemble(plan,LIVE));cache.close(); }
            else { ticket=null;try(var built=build(f,cache)) { check(built.scene().isPresent(),"publication fault fixture owns complete cached geometry"); }account.close(); }
            var primary=new AssertionError("injected retirement publication error");var secondary=same?primary:new AssertionError("injected retirement account-close error");
            try(ticket;var fault=new CaptureCloseFault(memory,primary,secondary)) {
                check(caughtFailure(lastTicket?ticket::close:cache::close)==primary,"retirement publication error remains primary");equal(lastTicket?3:2,fault.removals,"final cleanup and account close still attempted after retained-byte publication failure");equal(same?0:1,primary.getSuppressed().length,"distinct account-close error suppressed without self-suppression");if(!same)check(primary.getSuppressed()[0]==secondary,"later account error attached to original publication error");check(cache.stats().closed()&&cache.stats().sections()==0&&cache.stats().epochs()==0,"committed retirement never leaves a removed slot with stale counters");equal(0L,cache.stats().knownPayloadBytes(),"committed retirement releases actual cache graph");noWorkerCharge(memory);
            }
            cache.close();noWorkerCharge(memory);
        }
    }
    private static void pinnedReplacement() {
        cacheReservationFailures();
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
    /** Axis-aligned source planes are an independent oracle for closed clipping and detached coordinates. */
    private static void pickingFractionalPlanes() {
        var f=fixture(1,255,false);var offsets=WorldOffsets.read(f.snapshot(),"synthetic fractional picking offsets",cell->new WorldOffsets.Offset(1e-8,1e-8,1e-8),LIVE);
        var models=WorldModels.capture(f.snapshot(),f.layout(),List.of(WorldModels.Entry.from(f.prototype())),new int[]{0},offsets,WorldModels.Limits.DEFAULT,LIVE);check(!models.requiresPlaceholder(),"fractional world assignments complete");
        var plan=WorldScenePlan.plan(models,WorldVisibility.keepAll(f.snapshot(),f.layout()),LIVE);check(!plan.requiresPlaceholder(),"fractional world plan complete");
        try(var cache=new WorldSectionCache();var owner=WorldScene.build(plan,f.raster(),cache,LIVE);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
            check(!owner.requiresPlaceholder()&&!index.requiresPlaceholder(),"fractional world index complete");var position=f.snapshot().extent().position(0);double plane=(double)(float)1e-8;
            for(int axis=0;axis<3;axis++)for(boolean culling:new boolean[]{false,true}) {
                double[] origin={0.5,0.5,0.5},direction={0,0,0};origin[axis]=-1_000_000.1;direction[axis]=1;
                var ray=new WorldRay(position,origin[0],origin[1],origin[2],direction[0],direction[1],direction[2],0,2_097_152);var hit=index.query(ray,WorldPickIndex.Options.DEFAULT.withBatchCulling(culling),null,LIVE).hit().orElseThrow();
                double[] local={hit.localX(),hit.localY(),hit.localZ()};equal(plane,local[axis],"detached fractional normal retains the actual float vertex plane");equal(position,hit.position(),"fractional display offsets preserve native cell position");equal(state(407),hit.state(),"fractional display offsets preserve native state");near(1_000_000.1+plane,hit.distance(),"fractional plane distance");
                for(int tangent=0;tangent<3;tangent++)if(tangent!=axis)equal(0.5,local[tangent],"fractional hit tangent unchanged");
            }
        }
    }
    private static void pickingPlanes() {
        pickingFractionalPlanes();
        var f=fixture(1,255,false);var position=f.snapshot().extent().position(0);
        try(var cache=new WorldSectionCache();var owner=build(f,cache);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
            for(int side=0;side<6;side++)for(double distance:new double[]{0.1,0.3,1.1,7.7,31.9,128.125,1_000_000.1,1_048_574.75}) {
                int axis=side<2?1:side<4?2:0;boolean plus=(side&1)!=0;double plane=plus?1:0;
                double[] origin={0.25,0.25,0.25},point={0.375,0.375,0.375};point[(axis+1)%3]=0.625;origin[axis]=plus?1+distance:-distance;point[axis]=plane;
                double dx=point[0]-origin[0],dy=point[1]-origin[1],dz=point[2]-origin[2];
                var clip=new WorldClip(new WorldSnapshot.Extent(position.x()+(axis==0?(plus?1:-1):0),position.y()+(axis==1?(plus?1:-1):0),position.z()+(axis==2?(plus?1:-1):0),1,1,1));
                var ray=new WorldRay(position,origin[0],origin[1],origin[2],dx,dy,dz,0,2_097_152);WorldPick.Hit first=null;
                for(boolean culling:new boolean[]{false,true}) {
                    var options=WorldPickIndex.Options.DEFAULT.withClip(clip).withBatchCulling(culling);var result=index.query(ray,options,null,LIVE);check(!result.requiresPlaceholder(),"closed world-plane query complete");var hit=result.hit().orElseThrow();
                    equal(position,hit.position(),"closed plane retains exact native position");equal(state(407),hit.state(),"closed plane retains native state");equal(BIOME,hit.biome(),"closed plane retains native biome");equal(ModelPlan.Direction.values()[side],hit.surface().orElseThrow().source().modelFace(),"original face direction retained");near(Math.hypot(Math.hypot(dx,dy),dz),hit.distance(),"analytic world-plane distance");
                    double[] local={hit.localX(),hit.localY(),hit.localZ()};equal(plane,local[axis],"detached world normal is exact source plane");for(int tangent=0;tangent<3;tangent++)if(tangent!=axis)near(point[tangent],local[tangent],"detached tangent coordinate");
                    if(first==null)first=hit;else equal(first,hit,"range/exhaustive plane receipts agree");
                    var endpoint=new WorldRay(position,origin[0],origin[1],origin[2],dx,dy,dz,hit.distance(),hit.distance());check(index.query(endpoint,options,null,LIVE).hit().isPresent(),"closed world near/far endpoint retained");
                    check(index.query(new WorldRay(position,origin[0],origin[1],origin[2],dx,dy,dz,0,Math.nextDown(hit.distance())),options,null,LIVE).hit().isEmpty(),"strict world far exclusion unchanged");
                    check(index.query(new WorldRay(position,origin[0],origin[1],origin[2],dx,dy,dz,Math.nextUp(hit.distance()),2_097_152),options,null,LIVE).hit().isEmpty(),"strict world near exclusion unchanged");
                }
            }
        }
    }
    private static void pickingSectionFrames() {
        var f=fixture(1,255,false);var extent=f.snapshot().extent();
        var snapshot=WorldSnapshot.capture(f.snapshot().source(),new WorldSnapshot.Extent(extent.minX(),extent.minY(),extent.minZ(),2,2,2),List.of(state(407)),List.of(BIOME),new int[8],new int[8],LIVE);var position=snapshot.extent().position(7);
        var diagnostic=new ModelPlan.Problem(ModelPlan.Code.BUILTIN_MODEL,ClientAssetSnapshot.Kind.MODEL,MODEL,"","synthetic section-frame marker");
        for(boolean marker:new boolean[]{false,true}) {
            var entry=marker?WorldModels.Entry.placeholder(state(407),List.of(diagnostic)):WorldModels.Entry.from(f.prototype());var choices=new int[8];choices[7]=1;
            var models=WorldModels.capture(snapshot,f.layout(),List.of(WorldModels.Entry.empty(state(407)),entry),choices,LIVE);var visibility=WorldVisibility.keepAll(snapshot,f.layout());var plan=WorldScenePlan.plan(models,visibility,f.plan().limits(),f.plan().sectionLimits(),LIVE);
            check(!plan.requiresPlaceholder(),"offset section-frame plan complete");
            try(var cache=new WorldSectionCache();var owner=WorldScene.build(plan,f.raster(),cache,LIVE);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
                check(!owner.requiresPlaceholder()&&!index.requiresPlaceholder(),"offset section-frame index complete");equal(8,owner.scene().orElseThrow().sections().size(),"empty source sections retain original coverage");
                var clip=new WorldClip(new WorldSnapshot.Extent(position.x(),position.y(),position.z(),1,1,1));double epsilon=Math.scalb(1.0,-60);
                for(int axis=0;axis<3;axis++)for(boolean culling:new boolean[]{false,true}) {
                    int normal=(axis+1)%3;var options=WorldPickIndex.Options.DEFAULT.withClip(clip).withBatchCulling(culling);
                    for(boolean inside:new boolean[]{false,true}) {
                        double[] origin={0.5,0.5,0.5},direction={0,0,0};origin[axis]=inside?epsilon:-epsilon;origin[normal]=3;direction[normal]=-1;
                        var ray=new WorldRay(position,origin[0],origin[1],origin[2],direction[0],direction[1],direction[2],0,10);var result=index.query(ray,options,null,LIVE);check(!result.requiresPlaceholder(),"tiny section-frame query complete");
                        if(!inside) { check(result.hit().isEmpty(),"sub-ULP point outside exact section clip remains excluded");continue; }
                        var hit=result.hit().orElseThrow();double[] local={hit.localX(),hit.localY(),hit.localZ()};equal(epsilon,local[axis],"tiny section-local tangent survives index-frame rounding");equal(1.0,local[normal],"section-local normal remains exact");equal(position,hit.position(),"section-frame hit retains exact native cell");equal(state(407),hit.state(),"section-frame hit retains native state");equal(BIOME,hit.biome(),"section-frame hit retains native biome");equal(marker,hit.placeholder(),"actual primitive kind retained");near(2,hit.distance(),"section-frame analytic front distance");
                    }
                }
                if(marker) {
                    for(int side=0;side<6;side++)for(double distance:new double[]{0.1,0.3,7.7,1_000_000.1,1_048_574.75}) {
                        int axis=side<2?1:side<4?2:0;boolean plus=(side&1)!=0;double plane=plus?1:0;double[] origin={0.25,0.25,0.25},point={0.375,0.375,0.375};point[(axis+1)%3]=0.625;origin[axis]=plus?1+distance:-distance;point[axis]=plane;
                        double dx=point[0]-origin[0],dy=point[1]-origin[1],dz=point[2]-origin[2];var touching=new WorldClip(new WorldSnapshot.Extent(position.x()+(axis==0?(plus?1:-1):0),position.y()+(axis==1?(plus?1:-1):0),position.z()+(axis==2?(plus?1:-1):0),1,1,1));
                        for(boolean culling:new boolean[]{false,true}) {
                            var options=WorldPickIndex.Options.DEFAULT.withClip(touching).withBatchCulling(culling);var ray=new WorldRay(position,origin[0],origin[1],origin[2],dx,dy,dz,0,2_097_152);var hit=index.query(ray,options,null,LIVE).hit().orElseThrow();double[] local={hit.localX(),hit.localY(),hit.localZ()};equal(plane,local[axis],"marker receipt preserves exact accepted slab plane");equal(List.of(diagnostic),hit.visualProblems(),"marker section frame retains diagnostic");near(Math.hypot(Math.hypot(dx,dy),dz),hit.distance(),"marker analytic plane distance");
                            check(index.query(new WorldRay(position,origin[0],origin[1],origin[2],dx,dy,dz,hit.distance(),hit.distance()),options,null,LIVE).hit().isPresent(),"marker closed endpoint retained");
                            check(index.query(new WorldRay(position,origin[0],origin[1],origin[2],dx,dy,dz,0,Math.nextDown(hit.distance())),options,null,LIVE).hit().isEmpty(),"marker strict far limit retained");
                            check(index.query(new WorldRay(position,origin[0],origin[1],origin[2],dx,dy,dz,Math.nextUp(hit.distance()),2_097_152),options,null,LIVE).hit().isEmpty(),"marker strict near limit retained");
                        }
                    }
                    for(double minimum:new double[]{0,0.25,0.5}) { var hit=index.query(new WorldRay(position,0.5,0.5,0.5,1,0,0,minimum,1),LIVE).hit().orElseThrow();equal(minimum,hit.distance(),"inside marker starts at requested near distance");equal(0.5+minimum,hit.localX(),"inside marker receipt follows near distance");equal(0.5,hit.localY(),"inside marker tangent retained"); }
                }
            }
        }
    }
    private static void pickingTraversalEdges() {
        pickingSectionFrames();
        pickingPlanes();
        double edge=2e-11;var f=fixture(1,255,false);var position=f.snapshot().extent().position(0);
        try(var cache=new WorldSectionCache();var owner=build(f,cache);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
            for(var xy:new double[][]{{-edge,0.5},{1+edge,0.5},{0.5,-edge},{0.5,1+edge},{-edge,-edge},{1+edge,1+edge}}) {
                var ray=new WorldRay(position,xy[0],xy[1],3,0,0,-1,0,10);var result=index.query(ray,LIVE);check(!result.requiresPlaceholder(),"edge query remains complete");var hit=result.hit().orElseThrow();near(2,hit.distance(),"edge-tolerant front plane remains the analytic hit");equal(position,hit.position(),"hierarchy padding preserves exact native position");equal(hit,index.query(ray,WorldPickIndex.Options.DEFAULT.withBatchCulling(false),null,LIVE).hit().orElseThrow(),"range/exhaustive edge hits agree");
            }
            for(double outside:new double[]{5e-7,1e-5}) {
                var ray=new WorldRay(position,-outside,0.5,3,0,0,-1,0,10);for(boolean culling:new boolean[]{false,true}) { var result=index.query(ray,WorldPickIndex.Options.DEFAULT.withBatchCulling(culling),null,LIVE);check(!result.requiresPlaceholder()&&result.hit().isEmpty(),"candidate padding never invents a primitive hit"); }
            }
            var boundary=new WorldRay(position,0,0.5,3,0,0,-1,2,2);near(2,index.query(boundary,LIVE).hit().orElseThrow().distance(),"closed ray interval retains boundary hit");
            check(index.query(new WorldRay(position,0.5,0.5,3,0,0,-1,0,Math.nextDown(2.0)),LIVE).hit().isEmpty(),"candidate padding does not extend ray maximum");check(index.query(new WorldRay(position,0.5,0.5,3,0,0,-1,Math.nextUp(2.0),2.5),LIVE).hit().isEmpty(),"candidate padding does not extend ray minimum");
            var clip=new WorldClip(f.snapshot().extent());var clipped=WorldPickIndex.Options.DEFAULT.withClip(clip);check(index.query(new WorldRay(position,-edge,0.5,3,0,0,-1,0,10),clipped,null,LIVE).hit().isEmpty(),"geometric clipping stays exact outside the lower plane");check(index.query(new WorldRay(position,1+edge,0.5,3,0,0,-1,0,10),clipped,null,LIVE).hit().isEmpty(),"geometric clipping stays exact outside the upper plane");near(2,index.query(boundary,clipped,null,LIVE).hit().orElseThrow().distance(),"exact closed clip boundary remains inspectable");
            var shifted=new WorldSnapshot.Position(position.x()+4096,position.y(),position.z());var shiftedRay=new WorldRay(shifted,-4096-edge,0.5,3,0,0,-1,0,10);equal(position,index.query(shiftedRay,LIVE).hit().orElseThrow().position(),"exact large anchors preserve a small near-edge relative ray");
            var limited=index.query(new WorldRay(position,-edge,0.5,3,0,0,-1,0,10),new WorldPickIndex.Options(100,0,0,false,WorldPickIndex.Alpha.GEOMETRY),null,LIVE);check(limited.requiresPlaceholder()&&limited.hit().isEmpty(),"edge candidate respects atomic primitive-budget failure");equal(WorldProblem.Code.LIMIT,limited.problems().getFirst().code(),"edge budget reports the existing limit diagnostic");
        }
        var joined=fixture(2,255,false);try(var cache=new WorldSectionCache();var owner=build(joined,cache);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
            equal(2,owner.scene().orElseThrow().sections().size(),"tie fixture spans two hierarchy leaves");var origin=joined.snapshot().extent().position(0);var ray=new WorldRay(origin,1+edge,0.5,3,0,0,-1,0,10);
            for(boolean culling:new boolean[]{false,true}) { var hit=index.query(ray,WorldPickIndex.Options.DEFAULT.withBatchCulling(culling),null,LIVE).hit().orElseThrow();near(2,hit.distance(),"shared-edge tie remains at the front plane");equal(origin,hit.position(),"padded leaf preserves lower original-section tie order");equal(0,hit.section(),"lower section wins equal-distance edge tie"); }
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
    private static void orderViewPreflight() {
        var f=fixture(2,128,false);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            var anchor=f.snapshot().extent().position(0);var invalid=new ArrayList<WorldDrawOrder.View>();for(double distance:new double[]{1e-9,Math.nextDown(1e-8),Double.MIN_VALUE})invalid.add(new WorldDrawOrder.View(anchor,0,0,0,0,0,distance));
            for(var view:invalid) { var result=WorldDrawOrder.prepare(owner.scene().orElseThrow(),view,LIVE);check(result.requiresPlaceholder()&&result.size()==0&&result.bytes()==0,"invalid direction publishes no partial direct order");equal(WorldProblem.Code.COORDINATE,result.problems().getFirst().code(),"direct direction failure retains coordinate diagnostic"); }
            try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var worker=new WorldOrderWorker(4*1024*1024,memory);var gate=new OrderGate(orderExecutor(worker))) {
                var pending=owner.retain();long revision=worker.submit(pending,orderView(f));var before=worker.status();var admitted=memory.stats();long reserved=worker.reservedBytes();
                for(var view:invalid) { var rejected=owner.retain();throwsType(IllegalArgumentException.class,()->worker.submit(rejected,view),"invalid view rejected before pending replacement");check(rejected.closed()&&!pending.closed()&&worker.status()==before,"view rejection consumes only fresh source and preserves exact pending status");equal(admitted,memory.stats(),"view rejection preserves shared admission");equal(reserved,worker.reservedBytes(),"view rejection preserves local reservation"); }
                throwsType(IllegalArgumentException.class,()->worker.submit(pending,invalid.getFirst()),"already-owned wrapper stays protected before view validation");check(!pending.closed()&&worker.status()==before,"invalid duplicate view does not consume existing owner");
                var boundary=new WorldDrawOrder.View(anchor,0,0,0,0,0,1e-8);var exact=WorldDrawOrder.prepare(owner.scene().orElseThrow(),boundary,LIVE);var ordinary=WorldDrawOrder.prepare(owner.scene().orElseThrow(),new WorldDrawOrder.View(anchor,0,0,0,0,0,1),LIVE);check(!exact.requiresPlaceholder()&&Arrays.equals(exact.copyOrder(),ordinary.copyOrder()),"exact minimum direction retains ordinary axial face order");
                var accepted=owner.retain();equal(revision+1,worker.submit(accepted,boundary),"rejected views do not spend revisions and exact threshold is accepted");check(pending.closed()&&!accepted.closed(),"valid correction replaces only pending owner");worker.close();check(accepted.closed()&&!owner.closed(),"preflight sequence closes only worker sources");equal(0L,memory.stats().chargedBytes(),"preflight sequence releases shared bytes");equal(0,memory.stats().accounts(),"preflight sequence releases accounts");
            }
            try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var worker=new WorldOrderWorker(4*1024*1024,memory)) {
                long revision=worker.submit(owner.retain(),orderView(f));orderBarrier(orderExecutor(worker));var before=worker.status();var admitted=memory.stats();long reserved=worker.reservedBytes();
                var rejected=owner.retain();throwsType(IllegalArgumentException.class,()->worker.submit(rejected,invalid.getFirst()),"invalid view preserves ready output");check(rejected.closed()&&worker.status()==before,"ready status preserved exactly on view rejection");equal(admitted,memory.stats(),"ready shared charge preserved");equal(reserved,worker.reservedBytes(),"ready local reservation preserved");
                try(var completion=worker.take(revision).orElseThrow()) { check(!completion.owner().closed()&&!completion.order().requiresPlaceholder(),"earlier complete order remains transferable after invalid view"); }
                worker.close();equal(0L,memory.stats().chargedBytes(),"ready preflight sequence releases shared bytes");equal(0,memory.stats().accounts(),"ready preflight sequence releases accounts");
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
    /** Queues jobs without starting them and fails its first shutdown request with an exact supplied error. */
    private static final class ShutdownExecutor extends AbstractExecutorService {
        private final List<Runnable> queued=new ArrayList<>();private final Throwable failure;private boolean shutdown;private int attempts;
        ShutdownExecutor(boolean error) { failure=error?new AssertionError("injected shutdown error"):new IllegalStateException("injected shutdown failure"); }
        @Override public void execute(Runnable command) { if(shutdown)throw new RejectedExecutionException("test executor is closed");queued.add(Objects.requireNonNull(command)); }
        @Override public void shutdown() { shutdown=true; }
        @Override public List<Runnable> shutdownNow() {
            if(++attempts==1) { if(failure instanceof Error error)throw error;throw (RuntimeException)failure; }
            shutdown=true;var result=List.copyOf(queued);queued.clear();return result;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown&&queued.isEmpty(); }
        @Override public boolean awaitTermination(long timeout,TimeUnit unit) { return isTerminated(); }
        @Override public void close() { shutdown=true;queued.clear(); }
        void runAll() { while(!queued.isEmpty())queued.remove(0).run(); }
    }
    private static void firstShutdownFails(AutoCloseable worker,ShutdownExecutor executor) {
        try { worker.close(); }catch(Throwable failure) { check(failure==executor.failure,"exact shutdown failure remains primary");equal(1,executor.attempts,"first shutdown attempted once");return; }
        throw new AssertionError("injected shutdown failure was not reported");
    }
    private static void retryShutdown(AutoCloseable worker,ShutdownExecutor executor) {
        try { worker.close();equal(2,executor.attempts,"failed shutdown is retried despite closed input gate");check(executor.isTerminated(),"retry disposes queued executor work");worker.close();equal(2,executor.attempts,"successful shutdown is idempotent"); }
        catch(Exception failure) { throw new AssertionError("shutdown recovery failed",failure); }
    }
    private static void workerShutdownRecovery() {
        workerConstructionFailures();
        var f=fixture(2,128,false);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            for(boolean error:new boolean[]{false,true}) {
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new WorldOrderWorker(4*1024*1024,memory,()->executor)) {
                    var input=owner.retain();long revision=worker.submit(input,orderView(f));check(worker.reservedBytes()>0&&memory.stats().chargedBytes()>0,"pending sort is admitted");firstShutdownFails(worker,executor);
                    check(input.closed()&&!owner.closed(),"failed executor shutdown still releases pending sort source");equal(0L,worker.reservedBytes(),"sort local reservation cleared");equal(0L,memory.stats().chargedBytes(),"sort shared reservation cleared");equal(0,memory.stats().accounts(),"sort account released");equal(WorldOrderWorker.Phase.CLOSED,worker.status().phase(),"sort terminal status published");equal(revision,worker.status().revision(),"sort shutdown preserves revision");check(worker.take(revision).isEmpty(),"closed sort transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed sort rejects new invalidation");retryShutdown(worker,executor);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new WorldPickIndexWorker(4*1024*1024,memory,()->executor)) {
                    var input=owner.retain();long revision=worker.submit(input);check(worker.reservedBytes()>0&&memory.stats().chargedBytes()>0,"pending index preparation is admitted");firstShutdownFails(worker,executor);
                    check(input.closed()&&!owner.closed(),"failed executor shutdown still releases pending index source");equal(0L,worker.reservedBytes(),"index local reservation cleared");equal(0L,memory.stats().chargedBytes(),"index shared reservation cleared");equal(0,memory.stats().accounts(),"index account released");equal(WorldPickIndexWorker.Phase.CLOSED,worker.status().phase(),"index terminal status published");equal(revision,worker.status().revision(),"index shutdown preserves revision");check(worker.take(revision).isEmpty(),"closed index worker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed index worker rejects new invalidation");retryShutdown(worker,executor);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new WorldSceneWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(new WorldSceneWorker.Request(f.models(),f.visibility(),f.raster(),f.plan().limits(),WorldMesher.Limits.DEFAULT));check(memory.stats().chargedBytes()>0,"pending standalone scene is admitted");firstShutdownFails(worker,executor);
                    check(worker.cacheStats().closed(),"scene cache closes after executor failure");equal(0L,memory.stats().chargedBytes(),"scene reservation cleared");equal(0,memory.stats().accounts(),"scene cache account released despite executor failure");equal(WorldSceneWorker.Phase.CLOSED,worker.status().phase(),"scene terminal status published");equal(revision,worker.status().revision(),"scene shutdown preserves revision");check(worker.take(revision).isEmpty(),"closed scene worker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed scene worker rejects new invalidation");retryShutdown(worker,executor);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new WorldRenderWorker(4*1024*1024,memory,()->executor)) {
                    equal(1,memory.stats().accounts(),"integrated preparation owns a cache account before work");firstShutdownFails(worker,executor);
                    check(worker.cacheStats().closed(),"integrated cache closes after executor failure");equal(0L,memory.stats().chargedBytes(),"integrated idle reservation remains zero");equal(0,memory.stats().accounts(),"integrated cache account released despite executor failure");equal(WorldRenderWorker.Phase.CLOSED,worker.status().phase(),"integrated terminal status published");equal(0L,worker.status().revision(),"idle integrated shutdown preserves revision");throwsType(IllegalStateException.class,worker::invalidate,"closed integrated worker rejects new invalidation");retryShutdown(worker,executor);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new WorldSceneWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(new WorldSceneWorker.Request(f.models(),f.visibility(),f.raster(),f.plan().limits(),WorldMesher.Limits.DEFAULT));executor.runAll();equal(WorldSceneWorker.Phase.READY,worker.status().phase(),"controlled scene preparation publishes complete output");
                    try(var transferred=worker.take(revision).orElseThrow().result()) {
                        long retained=memory.stats().chargedBytes();check(retained>0,"transferred scene retains its cache account");firstShutdownFails(worker,executor);
                        check(worker.cacheStats().closed()&&worker.cacheStats().leases()>0&&!transferred.closed(),"failed shutdown retires cache without closing transferred scene");equal(retained,memory.stats().chargedBytes(),"external scene keeps exact charge after failed shutdown");retryShutdown(worker,executor);check(transferred.scene().isPresent(),"transferred scene survives shutdown retry");equal(retained,memory.stats().chargedBytes(),"successful shutdown retry keeps external lease charged");
                    }
                    equal(0L,memory.stats().chargedBytes(),"last transferred scene release clears retired cache charge");equal(0,memory.stats().accounts(),"last transferred scene release clears cache account");check(!owner.closed(),"independent fixture source remains live");
                }
            }
        }
    }
    /** Rejects the first scheduling attempt without running it, then queues a retry for deterministic inspection. */
    private static final class StartExecutor extends AbstractExecutorService {
        final Throwable failure;final List<Runnable> queued=new ArrayList<>();int attempts;boolean shutdown;
        StartExecutor(int kind) { failure=switch(kind) { case 0->new RejectedExecutionException("injected task rejection");case 1->new OutOfMemoryError("injected task startup OOM");default->new AssertionError("injected task startup error"); }; }
        @Override public void execute(Runnable command) {
            if(shutdown)throw new RejectedExecutionException("test executor closed");Objects.requireNonNull(command);
            if(++attempts==1) { if(failure instanceof Error error)throw error;throw (RuntimeException)failure; }queued.add(command);
        }
        @Override public void shutdown() { shutdown=true; }
        @Override public List<Runnable> shutdownNow() { shutdown=true;var result=List.copyOf(queued);queued.clear();return result; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown&&queued.isEmpty(); }
        @Override public boolean awaitTermination(long timeout,TimeUnit unit) { return isTerminated(); }
        @Override public void close() { shutdownNow(); }
    }
    private static void startRecovery(StartExecutor executor,LongSupplier submit,Supplier<String> phase,LongSupplier revision,Supplier<String> code,String ordinaryCode,RenderMemory memory,Runnable rejectedChecks,AutoCloseable worker) {
        Throwable actual=null;try { equal(1L,submit.getAsLong(),"ordinary scheduling rejection returns its admitted revision"); }catch(RuntimeException|Error failure) { actual=failure; }
        if(executor.failure instanceof Error&&!(executor.failure instanceof OutOfMemoryError))check(actual==executor.failure,"unexpected startup Error preserves exact identity");else if(actual!=null)throw new AssertionError("ordinary rejection/OOM must retain FAILED-token behavior",actual);
        equal(1,executor.attempts,"failed dispatch attempted once");check(executor.queued.isEmpty(),"rejected task was never queued");equal("FAILED",phase.get(),"startup failure publishes failed phase");equal(1L,revision.getAsLong(),"accepted failed revision is retained");equal(executor.failure instanceof OutOfMemoryError?"MEMORY":ordinaryCode,code.get(),"startup failure classification retained");equal(0L,memory.stats().chargedBytes(),"unscheduled input and scratch charges released");rejectedChecks.run();
        equal(2L,submit.getAsLong(),"retry spends exactly one new revision");equal(2,executor.attempts,"retry reaches executor after dispatch gate reset");equal(1,executor.queued.size(),"one latest retry task queued");equal("QUEUED",phase.get(),"retry queued phase");check(memory.stats().chargedBytes()>0,"queued retry remains admitted");
        try { worker.close(); }catch(Exception failure) { throw new AssertionError("startup recovery close failed",failure); }check(executor.isTerminated(),"close releases queued retry task");noWorkerCharge(memory);
    }
    private static void workerStartRecovery() {
        var f=fixture(2,128,false);var context=new MapSamples.Context(f.snapshot().source(),"test","startup","supplied synthetic samples");var colors=new MapRaster.ScalarStyle(-64,320,MapRamp.GRAYSCALE);
        var samples=MapSamples.terrain(context,new MapSamples.Grid(0,0,2,1,1),new MapSamples.Vertical(-64,384),new int[]{0,12},new byte[]{1,1},LIVE);
        var volume=NoiseVolume.capture(context,new NoiseVolume.Grid(new MapSamples.Grid(0,0,1,1,1),new MapSamples.Vertical(0,1)),new double[]{1},new byte[]{1},NoiseVolume.Limits.DEFAULT,LIVE);var style=new NoiseSurface.Style(1,1,colors);var surface=NoiseSurface.prepare(volume,style,NoiseSurface.Limits.DEFAULT,LIVE);
        var visualLimits=new WorldVisuals.Limits(4,4,4,16,16,1024*1024,4*1024*1024);var visuals=WorldVisuals.Request.capture(f.assets(),f.snapshot(),List.of(new WorldVisuals.EmptyChoice(0)),new int[2],0,0,visualLimits,LIVE);
        var encoded=SourceImage.Encoded.capture(new SourceImage.Identity("fixtures/startup.png",1),pixel(128));var imageLimits=new SourceImage.Limits(8,64,1024,256,1024*1024);
        try(var cache=new WorldSectionCache();var owner=build(f,cache);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
            for(int kind=0;kind<3;kind++) {
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new ClientAssetWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(new ClientAssetWorker.Request(Path.of("never-opened-startup-client.jar"),BlockModels.PROFILE,new ClientAssets.Limits(1024,1024,1024,16,4096))),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ equal(0L,worker.reservedBytes(),"failed archive startup holds no reservation");check(worker.take(1).isEmpty(),"failed archive task has no output"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new ImageDecodeWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(new ImageDecodeWorker.Request(encoded,imageLimits)),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ equal(0L,worker.reservedBytes(),"failed decode startup holds no reservation");check(worker.take(1).isEmpty(),"failed decode task has no output"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new MapRasterWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(new MapRasterWorker.Request(samples,colors)),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ equal(0L,worker.reservedBytes(),"failed map startup holds no reservation");check(worker.take(1).isEmpty(),"failed map task has no output"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new NoiseSurfaceWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(new NoiseSurfaceWorker.Request(volume,style,new NoiseSurface.Limits(16,1024*1024))),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ equal(0L,worker.reservedBytes(),"failed scalar startup holds no reservation");check(worker.take(1).isEmpty(),"failed scalar task has no output"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new WorldVisualWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(visuals),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ check(worker.take(1).isEmpty(),"failed visual task has no output"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new WorldSceneWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(new WorldSceneWorker.Request(f.models(),f.visibility(),f.raster(),f.plan().limits(),f.plan().sectionLimits())),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ check(worker.take(1).isEmpty(),"failed scene task has no output");check(!worker.cacheStats().closed(),"failed scene startup keeps cache available for retry"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new WorldRenderWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(new WorldRenderWorker.Request(visuals,WorldVisibility.Input.keepAll(f.snapshot()),f.plan().limits(),f.plan().sectionLimits(),4*1024*1024)),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ check(worker.take(1).isEmpty(),"failed render task has no output");check(!worker.cacheStats().closed(),"failed render startup keeps cache available for retry"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new AtlasFrameWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(AtlasFrameWorker.Request.capture(f.raster(),1,0)),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"ATLAS",memory,()->{ equal(0L,worker.reservedBytes(),"failed atlas startup holds no reservation");check(worker.take(1).isEmpty(),"failed atlas task has no output"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new WorldPickWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(new WorldPickWorker.Request(index,ray(f.snapshot()))),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ equal(0L,worker.reservedBytes(),"failed world query startup holds no reservation");check(worker.take(1).isEmpty()&&!index.closed(),"failed world query preserves borrowed index"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new NoisePickWorker(4*1024*1024,memory,()->executor)) {
                    startRecovery(executor,()->worker.submit(new NoisePickWorker.Request(surface,new WorldRay(new WorldSnapshot.Position(0,0,0),0.5,0.5,3,0,0,-1,0,10),new NoisePick.Options(16))),()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ equal(0L,worker.reservedBytes(),"failed scalar query startup holds no reservation");check(worker.take(1).isEmpty(),"failed scalar query has no output"); },worker);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new WorldOrderWorker(4*1024*1024,memory,()->executor)) {
                    var submitted=new ArrayList<WorldScene.Result>();LongSupplier submit=()->{ var fresh=owner.retain();submitted.add(fresh);return worker.submit(fresh,orderView(f)); };
                    startRecovery(executor,submit,()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ equal(0L,worker.reservedBytes(),"failed scene-owning startup holds no reservation");check(submitted.getFirst().closed()&&!owner.closed(),"failed startup consumes only its fresh scene wrapper");check(worker.take(1).isEmpty(),"failed scene-owning task has no output"); },worker);for(var supplied:submitted)check(supplied.closed(),"close consumes queued retry scene wrapper");
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new StartExecutor(kind);var worker=new WorldPickIndexWorker(4*1024*1024,memory,()->executor)) {
                    var submitted=new ArrayList<WorldScene.Result>();LongSupplier submit=()->{ var fresh=owner.retain();submitted.add(fresh);return worker.submit(fresh); };
                    startRecovery(executor,submit,()->worker.status().phase().name(),()->worker.status().revision(),()->worker.status().problems().getFirst().code().name(),"WORKER",memory,()->{ equal(0L,worker.reservedBytes(),"failed scene-owning startup holds no reservation");check(submitted.getFirst().closed()&&!owner.closed(),"failed startup consumes only its fresh scene wrapper");check(worker.take(1).isEmpty(),"failed scene-owning task has no output"); },worker);for(var supplied:submitted)check(supplied.closed(),"close consumes queued retry scene wrapper");
                }
            }
            check(!owner.closed()&&!index.closed(),"startup failure and retry preserve independent scene/index owners");equal(12,samples.height(1),"startup failure does not alter native map samples");near(1,volume.value(0),"startup failure does not alter scalar samples");equal(0L,f.raster().tick(),"startup failure does not alter atlas clock");
        }
    }
    /** Runs queued tasks explicitly; a recovery dispatch can independently fail before accepting its task. */
    private static final class DrainExecutor extends AbstractExecutorService {
        final List<Runnable> queued=new ArrayList<>();Throwable nextFailure;int attempts;boolean shutdown;
        @Override public void execute(Runnable command) {
            if(shutdown)throw new RejectedExecutionException("test executor closed");attempts++;
            if(nextFailure!=null) { var failure=nextFailure;nextFailure=null;if(failure instanceof Error error)throw error;throw (RuntimeException)failure; }queued.add(Objects.requireNonNull(command));
        }
        void runNext() { queued.remove(0).run(); }
        @Override public void shutdown() { shutdown=true; }
        @Override public List<Runnable> shutdownNow() { shutdown=true;var result=List.copyOf(queued);queued.clear();return result; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown&&queued.isEmpty(); }
        @Override public boolean awaitTermination(long timeout,TimeUnit unit) { return isTerminated(); }
        @Override public void close() { shutdownNow(); }
    }
    /** Test-only scene read fault: sizes and all reads after the first retain the original immutable data. */
    private static final class FaultSections extends AbstractList<WorldMesh> {
        final List<WorldMesh> original;final Runnable beforeFailure;final Error failure;boolean fired;
        FaultSections(List<WorldMesh> original,Runnable beforeFailure,Error failure) { this.original=original;this.beforeFailure=beforeFailure;this.failure=failure; }
        @Override public int size() { return original.size(); }
        @Override public WorldMesh get(int index) { if(!fired) { fired=true;beforeFailure.run();throw failure; }return original.get(index); }
    }
    private static void replaceSections(WorldScene scene,List<WorldMesh> sections) {
        try { var field=WorldScene.class.getDeclaredField("sections");field.setAccessible(true);field.set(scene,sections); }
        catch(ReflectiveOperationException failure) { throw new AssertionError("controlled source fault unavailable",failure); }
    }
    private static void drainRecovery(WorldScene.Result owner,DrainExecutor executor,LongSupplier submit,Supplier<String> phase,LongSupplier revision,LongSupplier reserved,LongConsumer takeReady,List<WorldScene.Result> supplied,RenderMemory memory,int mode) {
        equal(1L,submit.getAsLong(),"initial drain request admitted");var scene=owner.scene().orElseThrow();var original=scene.sections();var primary=new AssertionError("injected active scene read error");
        Throwable dispatch=mode==2?new RejectedExecutionException("injected recovery rejection"):mode==3?new AssertionError("injected recovery dispatch error"):null;
        var fault=new FaultSections(original,()->{ if(mode>0) { equal(2L,submit.getAsLong(),"replacement accepted during active source read");executor.nextFailure=dispatch; } },primary);replaceSections(scene,fault);
        try {
            Throwable actual=null;try { executor.runNext(); }catch(RuntimeException|Error failure) { actual=failure; }check(actual==primary&&fault.fired,"escaping task error retains exact identity");check(supplied.getFirst().closed()&&!owner.closed(),"failed active task releases only its fresh source");
            long expected;
            if(mode==0) {
                equal("FAILED",phase.get(),"current aborted task reports failure");equal(1L,revision.getAsLong(),"failed active revision retained");equal(0L,reserved.getAsLong(),"failed active reservation released");equal(0L,memory.stats().chargedBytes(),"failed active shared charge released");check(executor.queued.isEmpty(),"no automatic task without pending work");expected=submit.getAsLong();equal(2L,expected,"later submission retries stopped drain");
            }else if(mode==1) {
                equal("QUEUED",phase.get(),"old failure does not overwrite newer pending status");equal(2L,revision.getAsLong(),"pending revision retained");equal(1,executor.queued.size(),"already accepted replacement automatically redispatched");check(!supplied.get(1).closed(),"replacement source remains live");expected=2;
            }else {
                equal("FAILED",phase.get(),"failed recovery dispatch reports pending failure");equal(2L,revision.getAsLong(),"rejected pending revision remains spent");check(supplied.get(1).closed(),"unscheduled replacement source consumed");equal(0L,reserved.getAsLong(),"recovery rejection releases local reservation");equal(0L,memory.stats().chargedBytes(),"recovery rejection releases shared admission");check(executor.queued.isEmpty(),"rejected recovery task absent");check(Arrays.stream(primary.getSuppressed()).anyMatch(value->value==dispatch),"recovery dispatch failure suppressed onto active error");expected=submit.getAsLong();equal(3L,expected,"fresh request retries failed recovery dispatch");
            }
            equal(1,executor.queued.size(),"exactly one surviving drain queued");check(memory.stats().chargedBytes()>0,"surviving source remains admitted");executor.runNext();equal("READY",phase.get(),"surviving task publishes complete output");equal(expected,revision.getAsLong(),"ready revision matches surviving request");check(executor.queued.isEmpty(),"no duplicate recovery drain");takeReady.accept(expected);equal(0L,reserved.getAsLong(),"transferred output absent from worker reservation");equal(0L,memory.stats().chargedBytes(),"closed completion releases shared source/output charge");for(var input:supplied)check(input.closed(),"all transferred worker scene wrappers eventually close");
        }finally { replaceSections(scene,original); }
    }
    private static void workerDrainRecovery() {
        var f=fixture(2,128,false);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            for(int mode=0;mode<4;mode++) {
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new DrainExecutor();var worker=new WorldOrderWorker(4*1024*1024,memory,()->executor)) {
                    var supplied=new ArrayList<WorldScene.Result>();LongSupplier submit=()->{ var fresh=owner.retain();supplied.add(fresh);return worker.submit(fresh,orderView(f)); };
                    drainRecovery(owner,executor,submit,()->worker.status().phase().name(),()->worker.status().revision(),worker::reservedBytes,revision->{ try(var result=worker.take(revision).orElseThrow()) { check(!result.order().requiresPlaceholder()&&result.order().scene()==owner.scene().orElseThrow(),"recovered order uses exact original scene");equal(12,result.order().size(),"recovered order retains complete face coverage"); } },supplied,memory,mode);worker.close();noWorkerCharge(memory);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new DrainExecutor();var worker=new WorldPickIndexWorker(4*1024*1024,memory,()->executor)) {
                    var supplied=new ArrayList<WorldScene.Result>();LongSupplier submit=()->{ var fresh=owner.retain();supplied.add(fresh);return worker.submit(fresh); };
                    drainRecovery(owner,executor,submit,()->worker.status().phase().name(),()->worker.status().revision(),worker::reservedBytes,revision->{ try(var result=worker.take(revision).orElseThrow()) { check(result.index().matches(owner.scene().orElseThrow()),"recovered index uses exact original scene");var hit=result.index().query(ray(f.snapshot()),LIVE).hit().orElseThrow();equal(state(407),hit.state(),"recovered index preserves native state");equal(BIOME,hit.biome(),"recovered index preserves native biome"); } },supplied,memory,mode);worker.close();noWorkerCharge(memory);
                }
            }
            check(!owner.closed(),"drain recovery preserves independently retained scene");
        }
    }
    private static void noWorkerCharge(RenderMemory memory) { equal(0L,memory.stats().chargedBytes(),"closed worker has no idle shared charge");equal(0,memory.stats().accounts(),"closed worker has no idle account"); }
    private static void remainingWorkerShutdownRecovery() {
        activeWorldAdmission();
        workerDrainRecovery();
        workerStartRecovery();
        var f=fixture(2,128,false);var context=new MapSamples.Context(f.snapshot().source(),"test","shutdown","supplied synthetic samples");
        var samples=MapSamples.terrain(context,new MapSamples.Grid(0,0,2,1,1),new MapSamples.Vertical(-64,384),new int[]{0,12},new byte[]{1,1},LIVE);var colors=new MapRaster.ScalarStyle(-64,320,MapRamp.GRAYSCALE);
        var volume=NoiseVolume.capture(context,new NoiseVolume.Grid(new MapSamples.Grid(0,0,1,1,1),new MapSamples.Vertical(0,1)),new double[]{1},new byte[]{1},NoiseVolume.Limits.DEFAULT,LIVE);var style=new NoiseSurface.Style(1,1,colors);var surface=NoiseSurface.prepare(volume,style,NoiseSurface.Limits.DEFAULT,LIVE);check(!surface.requiresPlaceholder(),"shutdown scalar fixture is complete");
        try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            for(boolean error:new boolean[]{false,true}) {
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new ClientAssetWorker(4*1024*1024,memory,()->executor)) {
                    var limits=new ClientAssets.Limits(1024,1024,1024,16,4096);long revision=worker.submit(new ClientAssetWorker.Request(Path.of("never-opened-fixture-client.jar"),BlockModels.PROFILE,limits));check(worker.reservedBytes()>0,"queued archive metadata is admitted without reading a file");firstShutdownFails(worker,executor);equal(ClientAssetWorker.Phase.CLOSED,worker.status().phase(),"asset terminal status survives executor failure");equal(revision,worker.status().revision(),"asset revision preserved");equal(0L,worker.reservedBytes(),"queued archive reservation released");noWorkerCharge(memory);check(worker.take(revision).isEmpty(),"closed asset worker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed asset worker rejects new work");retryShutdown(worker,executor);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new ImageDecodeWorker(4*1024*1024,memory,()->executor)) {
                    var encoded=SourceImage.Encoded.capture(new SourceImage.Identity("fixtures/shutdown.png",1),pixel(128));var limits=new SourceImage.Limits(8,64,1024,256,1024*1024);long revision=worker.submit(new ImageDecodeWorker.Request(encoded,limits));check(worker.reservedBytes()>0,"queued encoded image is admitted");firstShutdownFails(worker,executor);equal(ImageDecodeWorker.Phase.CLOSED,worker.status().phase(),"image terminal status survives executor failure");equal(revision,worker.status().revision(),"image revision preserved");equal(0L,worker.reservedBytes(),"queued decode reservation released");noWorkerCharge(memory);check(worker.take(revision).isEmpty(),"closed image worker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed image worker rejects new work");retryShutdown(worker,executor);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new MapRasterWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(new MapRasterWorker.Request(samples,colors));check(worker.reservedBytes()>0,"queued map samples are admitted");firstShutdownFails(worker,executor);equal(MapRasterWorker.Phase.CLOSED,worker.status().phase(),"map terminal status survives executor failure");equal(revision,worker.status().revision(),"map revision preserved");equal(0L,worker.reservedBytes(),"queued map reservation released");noWorkerCharge(memory);check(worker.take(revision).isEmpty(),"closed map worker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed map worker rejects new work");retryShutdown(worker,executor);equal(12,samples.height(1),"caller map samples remain unchanged");
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new NoiseSurfaceWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(new NoiseSurfaceWorker.Request(volume,style,new NoiseSurface.Limits(16,1024*1024)));check(worker.reservedBytes()>0,"queued scalar volume is admitted");firstShutdownFails(worker,executor);equal(NoiseSurfaceWorker.Phase.CLOSED,worker.status().phase(),"scalar mesh terminal status survives executor failure");equal(revision,worker.status().revision(),"scalar mesh revision preserved");equal(0L,worker.reservedBytes(),"queued scalar reservation released");noWorkerCharge(memory);check(worker.take(revision).isEmpty(),"closed scalar worker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed scalar worker rejects new work");retryShutdown(worker,executor);near(1,volume.value(0),"caller scalar samples remain unchanged");
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new WorldVisualWorker(4*1024*1024,memory,()->executor)) {
                    var limits=new WorldVisuals.Limits(4,4,4,16,16,1024*1024,4*1024*1024);var request=WorldVisuals.Request.capture(f.assets(),f.snapshot(),List.of(new WorldVisuals.EmptyChoice(0)),new int[2],0,0,limits,LIVE);long revision=worker.submit(request);check(memory.stats().chargedBytes()>0,"queued visual request is admitted");firstShutdownFails(worker,executor);equal(WorldVisualWorker.Phase.CLOSED,worker.status().phase(),"visual terminal status survives executor failure");equal(revision,worker.status().revision(),"visual revision preserved");noWorkerCharge(memory);check(worker.take(revision).isEmpty(),"closed visual worker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed visual worker rejects new work");retryShutdown(worker,executor);
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new AtlasFrameWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(AtlasFrameWorker.Request.capture(f.raster(),1,0));check(worker.reservedBytes()>0,"queued atlas plan is admitted");firstShutdownFails(worker,executor);equal(AtlasFrameWorker.Phase.CLOSED,worker.status().phase(),"atlas terminal status survives executor failure");equal(revision,worker.status().revision(),"atlas revision preserved");equal(0L,worker.reservedBytes(),"queued atlas reservation released");noWorkerCharge(memory);check(worker.take(revision).isEmpty(),"closed atlas worker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed atlas worker rejects new work");retryShutdown(worker,executor);equal(0L,f.raster().tick(),"caller atlas clock remains unchanged");
                }
                try(var index=WorldPickIndex.prepare(owner.retain(),LIVE);var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new WorldPickWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(new WorldPickWorker.Request(index,ray(f.snapshot())));check(worker.reservedBytes()>0,"queued world pointer source is admitted");firstShutdownFails(worker,executor);equal(WorldPickWorker.Phase.CLOSED,worker.status().phase(),"world pointer terminal status survives executor failure");equal(revision,worker.status().revision(),"world pointer revision preserved");equal(0L,worker.reservedBytes(),"queued world pointer reservation released");noWorkerCharge(memory);check(worker.take(revision).isEmpty(),"closed world picker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed world picker rejects new work");retryShutdown(worker,executor);check(!index.closed()&&index.query(ray(f.snapshot()),LIVE).hit().isPresent(),"borrowed world picking index remains live and usable");
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new NoisePickWorker(4*1024*1024,memory,()->executor)) {
                    var ray=new WorldRay(new WorldSnapshot.Position(0,0,0),0.5,0.5,3,0,0,-1,0,10);long revision=worker.submit(new NoisePickWorker.Request(surface,ray,new NoisePick.Options(16)));check(worker.reservedBytes()>0,"queued scalar pointer source is admitted");firstShutdownFails(worker,executor);equal(NoisePickWorker.Phase.CLOSED,worker.status().phase(),"scalar pointer terminal status survives executor failure");equal(revision,worker.status().revision(),"scalar pointer revision preserved");equal(0L,worker.reservedBytes(),"queued scalar pointer reservation released");noWorkerCharge(memory);check(worker.take(revision).isEmpty(),"closed scalar picker transfers no output");throwsType(IllegalStateException.class,worker::invalidate,"closed scalar picker rejects new work");retryShutdown(worker,executor);check(NoisePick.query(surface,ray,16,LIVE).hit().isPresent(),"borrowed scalar surface remains usable");
                }
                try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new ShutdownExecutor(error);var worker=new MapRasterWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(new MapRasterWorker.Request(samples,colors));executor.runAll();try(var transferred=worker.take(revision).orElseThrow()) {
                        long retained=memory.stats().chargedBytes();check(retained>0,"transferred raster keeps its admission");firstShutdownFails(worker,executor);equal(0L,worker.reservedBytes(),"transferred raster absent from local worker reservation");equal(retained,memory.stats().chargedBytes(),"failed shutdown preserves transferred raster charge");retryShutdown(worker,executor);check(transferred.raster().samples()==samples,"transferred raster source survives shutdown retry");equal(retained,memory.stats().chargedBytes(),"successful retry preserves transferred raster charge");
                    }noWorkerCharge(memory);
                }
            }
            check(!owner.closed(),"all worker shutdown paths preserve the independent source scene");
        }
    }
    private static void emptyAndMarkers() {
        var snapshot=snapshot(1);var assets=assets(255);var layout=AtlasBuilder.empty(assets,BLOCK,LIVE);var raster=AtlasImages.capture(layout,0,0,LIVE);equal(0,raster.pages().size(),"empty atlas needs no dummy texture");
        var diagnostic=new ModelPlan.Problem(ModelPlan.Code.BUILTIN_MODEL,ClientAssetSnapshot.Kind.MODEL,MODEL,"","synthetic unsupported native visual");
        for(boolean marker:new boolean[]{false,true}) {
            var entry=marker?WorldModels.Entry.placeholder(snapshot.states().getFirst(),List.of(diagnostic)):WorldModels.Entry.empty(snapshot.states().getFirst());var models=WorldModels.capture(snapshot,layout,List.of(entry),new int[]{0},LIVE);var plan=WorldScenePlan.plan(models,WorldVisibility.keepAll(snapshot,layout),LIVE);
            try(var cache=new WorldSectionCache();var owner=WorldScene.build(plan,raster,cache,LIVE);var index=WorldPickIndex.prepare(owner.retain(),LIVE)) {
                check(!owner.requiresPlaceholder()&&!index.requiresPlaceholder(),"complete diagnostic/empty scene");equal(0,owner.scene().orElseThrow().usage().faces(),"no fabricated block faces");var picked=index.query(ray(snapshot),LIVE);
                if(marker) { var hit=picked.hit().orElseThrow();check(hit.placeholder()&&hit.surface().isEmpty(),"diagnostic marker hit");equal(snapshot.states().getFirst(),hit.state(),"marker retains unsupported original state");equal(List.of(diagnostic),hit.visualProblems(),"exact visual diagnostic");near(2,hit.distance(),"analytic marker box");
                    var outside=new WorldRay(snapshot.extent().position(0),-2e-11,0.5,3,0,0,-1,0,10);for(boolean culling:new boolean[]{false,true})check(index.query(outside,WorldPickIndex.Options.DEFAULT.withBatchCulling(culling),null,LIVE).hit().isEmpty(),"traversal padding never expands the actual marker box"); }
                else check(picked.hit().isEmpty(),"explicit empty produces no pick");
            }
        }
    }
    /** Faults one ticket lookup during final scene release; remaining tickets must still be visited. */
    private static final class TicketFault implements AutoCloseable {
        final Object shared;final java.lang.reflect.Field field;final List<WorldSectionCache.Ticket> original;final Throwable failure;int visits;boolean fired;
        @SuppressWarnings("unchecked") TicketFault(WorldScene.Result owner,Throwable failure) {
            this.failure=failure;
            try {
                var lease=WorldScene.Result.class.getDeclaredField("shared");lease.setAccessible(true);shared=lease.get(owner);check(shared!=null,"fault fixture owns a live scene");
                field=shared.getClass().getDeclaredField("tickets");field.setAccessible(true);original=(List<WorldSectionCache.Ticket>)field.get(shared);
                field.set(shared,new AbstractList<WorldSectionCache.Ticket>() {
                    @Override public int size() { return original.size(); }
                    @Override public WorldSectionCache.Ticket get(int index) { visits++;if(!fired&&index==0) { fired=true;if(TicketFault.this.failure instanceof RuntimeException runtime)throw runtime;throw (Error)TicketFault.this.failure; }return original.get(index); }
                });
            }catch(ReflectiveOperationException error) { throw new AssertionError("controlled scene release fault unavailable",error); }
        }
        @Override public void close() {
            try { field.set(shared,original); }catch(ReflectiveOperationException error) { throw new AssertionError("scene release fault restore failed",error); }
            for(var ticket:original)ticket.close();
        }
        void allVisited() { check(fired,"scene release fault was reached");equal(original.size(),visits,"source release visits every ticket despite first failure"); }
    }
    private static Throwable caughtFailure(Runnable action) {
        try { action.run(); }catch(RuntimeException|Error failure) { return failure; }throw new AssertionError("expected cleanup failure missing");
    }
    /** Read-only lifecycle inspection; source gates never rewrite worker jobs or admission. */
    private static Object workerField(Object worker,String name) {
        try { var field=worker.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(worker); }
        catch(ReflectiveOperationException failure) { throw new AssertionError("controlled worker ownership inspection unavailable",failure); }
    }
    /** Same captured catalog entries; one lookup pauses preparation for synchronous admission checks. */
    private static final class AssetLookupGate implements AutoCloseable {
        final ClientAssetSnapshot assets;final java.lang.reflect.Field field;final NavigableMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset> original;boolean fired;
        @SuppressWarnings("unchecked") AssetLookupGate(ClientAssetSnapshot assets,Runnable beforeLookup) {
            this.assets=assets;
            try {
                field=ClientAssetSnapshot.class.getDeclaredField("assets");field.setAccessible(true);original=(NavigableMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset>)field.get(assets);
                field.set(assets,new TreeMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset>(original) {
                    @Override public ClientAssetSnapshot.Asset get(Object key) { if(!fired) { fired=true;beforeLookup.run(); }return super.get(key); }
                });
            }catch(ReflectiveOperationException failure) { throw new AssertionError("controlled catalog lookup gate unavailable",failure); }
        }
        @Override public void close() { try { field.set(assets,original); }catch(ReflectiveOperationException failure) { throw new AssertionError("catalog lookup gate restore failed",failure); } }
    }
    private static void activeWorldAdmission() {
        var f=fixture(2,128,false);var visualLimits=new WorldVisuals.Limits(4,4,4,16,16,1024*1024,4*1024*1024);
        var visuals=WorldVisuals.Request.capture(f.assets(),f.snapshot(),List.of(new WorldVisuals.ModelChoice(0,List.of(0))),new int[2],0,0,visualLimits,LIVE);
        var keep=WorldVisibility.Input.keepAll(f.snapshot());var masks=WorldVisibility.Input.capture(f.snapshot(),"supplied zero masks",new byte[2],LIVE);
        var decisions=WorldModelInputs.Request.capture(f.assets(),f.snapshot(),List.of(WorldModelInputs.Decision.model()),0,0,new WorldModelInputs.Limits(visualLimits,BlockModels.Limits.DEFAULT,4,16),LIVE);
        long resident=f.plan().usage().orElseThrow().knownPayloadBytes(),activeInput=visuals.inputBytes()+keep.bytes(),activePeak=resident+activeInput;check(resident>masks.bytes(),"fixture has a nontrivial old cache/input coexistence peak");
        var activeRequest=new WorldRenderWorker.Request(visuals,keep,f.plan().limits(),f.plan().sectionLimits(),activeInput);
        for(boolean tracked:new boolean[]{false,true})for(boolean modelPending:new boolean[]{false,true}) {
            long pendingInput=(modelPending?decisions.inputBytes():visuals.inputBytes())+keep.bytes(),limit=activePeak+pendingInput;
            var exact=new WorldRenderWorker.Request(visuals,keep,f.plan().limits(),f.plan().sectionLimits(),limit);var over=new WorldRenderWorker.Request(visuals,masks,f.plan().limits(),f.plan().sectionLimits(),limit);
            var exactModel=new WorldRenderWorker.ModelRequest(decisions,keep,f.plan().limits(),f.plan().sectionLimits(),limit);var overModel=new WorldRenderWorker.ModelRequest(decisions,masks,f.plan().limits(),f.plan().sectionLimits(),limit);
            try(var memory=new RenderMemory(new RenderMemory.Limits(limit*4,4));var executor=new DrainExecutor();var worker=new WorldRenderWorker(limit,tracked?memory:null,()->executor)) {
                var cache=(WorldSectionCache)workerField(worker,"cache");
                // Populate through real cache/scene APIs, then leave only resident unleased payload before submission.
                try(var seed=build(f,cache)) { equal(resident,cache.stats().knownPayloadBytes(),"independent complete-scene receipt predicts cached source/mesh bytes"); }
                equal(0L,cache.stats().leases(),"prior scene leaves only unleased cache payload");
                LongSupplier submitExact=modelPending?()->worker.submit(exactModel):()->worker.submit(exact);LongSupplier submitOver=modelPending?()->worker.submit(overModel):()->worker.submit(over);
                try(var gate=new AssetLookupGate(f.assets(),()->{
                    var active=workerField(worker,"active");check(active!=null,"catalog lookup occurs inside active preparation");equal(activePeak,(long)workerField(active,"admissionBytes"),"active peak covers original cache plus frozen input");equal(0L,cache.stats().knownPayloadBytes(),"old cache was evicted but its admitted execution peak remains");
                    var status=worker.status();var shared=memory.stats();var cached=cache.stats();var cancelled=(java.util.concurrent.atomic.AtomicBoolean)workerField(active,"cancelled");check(!cancelled.get(),"active request initially uncancelled");
                    throwsType(IllegalArgumentException.class,()->submitOver.getAsLong(),"active peak plus larger pending input exceeds local bound even with shared headroom");equal(status,worker.status(),"local rejection preserves exact current status/revision");equal(shared,memory.stats(),"local rejection preserves exact shared admission");equal(cached,cache.stats(),"local rejection performs no cache mutation");check(workerField(worker,"pending")==null&&!cancelled.get(),"local rejection retains active ownership without installing work");
                    equal(2L,submitExact.getAsLong(),"exact active-peak plus pending-input boundary accepted");var pending=workerField(worker,"pending");check(pending!=null&&cancelled.get(),"accepted latest input cancels active work cooperatively");equal(tracked?limit:0,memory.stats().chargedBytes(),"exact acceptance stays within shared and local ceiling");
                    var queued=worker.status();var admitted=memory.stats();throwsType(IllegalArgumentException.class,()->submitOver.getAsLong(),"over-bound replacement cannot displace an admitted pending request");check(workerField(worker,"pending")==pending,"rejected replacement preserves exact pending request");equal(queued,worker.status(),"rejected replacement preserves accepted token/status");equal(admitted,memory.stats(),"rejected replacement keeps exact pending charge");
                    equal(3L,submitExact.getAsLong(),"exact replacement reuses the one pending slot without double charging");equal(tracked?limit:0,memory.stats().chargedBytes(),"exact replacement retains one pending charge");equal(1,executor.attempts,"active drain owns all submissions without duplicate dispatch");worker.close();equal(tracked?activePeak:0,memory.stats().chargedBytes(),"close drops pending input but retains the active execution peak through unwind");
                })) {
                    equal(1L,worker.submit(activeRequest),"cache/input coexistence job admitted before its later phase budget");executor.runNext();check(gate.fired,"active preparation reached controlled catalog lookup");
                }
                equal(WorldRenderWorker.Phase.CLOSED,worker.status().phase(),"active cancellation retains terminal metadata");equal(3L,worker.status().revision(),"only accepted submissions spend revisions");check(worker.take(1).isEmpty()&&worker.take(3).isEmpty(),"closed cancelled preparation publishes no scene");noWorkerCharge(memory);check(cache.stats().closed()&&cache.stats().leases()==0,"active unwind retires empty cache");equal(state(407),f.snapshot().states().getFirst(),"admission checks preserve original native state");equal(BIOME,f.snapshot().biomes().getFirst(),"admission checks preserve original native biome");
            }
        }
    }
    private static void sceneCompletionCleanupFailures() {
        var f=fixture(2,128,false);var sceneRequest=new WorldSceneWorker.Request(f.models(),f.visibility(),f.raster(),f.plan().limits(),f.plan().sectionLimits());
        var visualLimits=new WorldVisuals.Limits(4,4,4,16,16,1024*1024,4*1024*1024);
        var visuals=WorldVisuals.Request.capture(f.assets(),f.snapshot(),List.of(new WorldVisuals.ModelChoice(0,List.of(0))),new int[2],0,0,visualLimits,LIVE);
        var renderRequest=new WorldRenderWorker.Request(visuals,WorldVisibility.Input.keepAll(f.snapshot()),f.plan().limits(),f.plan().sectionLimits(),4*1024*1024);
        for(boolean integrated:new boolean[]{false,true})for(int action=0;action<3;action++)for(int kind=0;kind<3;kind++) {
            try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new DrainExecutor();
                var scene=integrated?null:new WorldSceneWorker(4*1024*1024,memory,()->executor);
                var render=integrated?new WorldRenderWorker(4*1024*1024,memory,()->executor):null) {
                Object worker=integrated?render:scene;
                LongSupplier submit=integrated?()->render.submit(renderRequest):()->scene.submit(sceneRequest);
                LongSupplier invalidate=integrated?render::invalidate:scene::invalidate;
                LongSupplier revision=integrated?()->render.status().revision():()->scene.status().revision();
                Supplier<String> phase=integrated?()->render.status().phase().name():()->scene.status().phase().name();
                Supplier<WorldSectionCache.Stats> cache=integrated?render::cacheStats:scene::cacheStats;
                Supplier<RenderMemory.Owner> usage=integrated?()->render.memoryUsage().orElseThrow():()->scene.memoryUsage().orElseThrow();
                java.util.function.LongFunction<Optional<WorldScene.Result>> take=integrated?token->render.take(token).map(WorldRenderWorker.Completion::owner):token->scene.take(token).map(WorldSceneWorker.Completion::result);
                Runnable close=integrated?render::close:scene::close;
                equal(1L,submit.getAsLong(),"initial scene request accepted");executor.runNext();equal("READY",phase.get(),"scene completion ready before release fault");
                var old=(WorldScene.Result)workerField(worker,"completed");check(old!=null&&!old.closed(),"worker owns live untaken scene");long retained=memory.stats().chargedBytes();check(retained>0&&retained<4*1024*1024,"READY cache is smaller than new working reservation");equal(0L,usage.get().reservedBytes(),"READY worker has no job reservation after unwind");
                Throwable primary=switch(kind) { case 0->new IllegalStateException("injected READY source release failure");case 1->new OutOfMemoryError("injected READY source release OOM");default->new AssertionError("injected READY source release error"); };
                try(var fault=new TicketFault(old,primary)) {
                    Throwable actual=action==0?caughtFailure(()->invalidate.getAsLong()):action==1?caughtFailure(()->submit.getAsLong()):caughtFailure(close);
                    check(actual==primary,"scene disposal preserves exact source release failure");fault.allVisited();check(old.closed(),"failed scene release consumes its wrapper");check(workerField(worker,"completed")==null,"dropped scene is detached before release");if(integrated)check(workerField(worker,"completedTint")==null,"dropped scene tint is detached with its owner");
                    check(take.apply(1).isEmpty(),"failed disposal exposes no closed scene completion");equal(1L,revision.getAsLong(),"failed disposal does not spend a replacement revision");equal(0L,usage.get().reservedBytes(),"failed disposal removes any uninstalled replacement reservation");check(executor.queued.isEmpty(),"failed replacement dispatches no new task");
                    check(cache.get().leases()>0,"deliberately skipped external ticket remains charged");if(action!=2) { equal(retained,memory.stats().chargedBytes(),"failed cancellation keeps actual cache charge without new peak");equal("READY",phase.get(),"failed cancellation leaves prior metadata snapshot"); }
                    else { check(cache.get().closed(),"shutdown still retires cache after source release failure");equal("CLOSED",phase.get(),"shutdown still publishes terminal metadata"); }
                }
                equal(0L,cache.get().leases(),"fixture restores and closes deliberately skipped ticket");
                if(action==2) { close.run();noWorkerCharge(memory);continue; }
                equal(cache.get().knownPayloadBytes(),memory.stats().chargedBytes(),"only actual resident cache payload remains charged");equal(2L,submit.getAsLong(),"fresh request retries without skipped revision");equal(1,executor.queued.size(),"retry schedules exactly one drain");executor.runNext();equal("READY",phase.get(),"retry publishes complete scene");
                try(var result=take.apply(2).orElseThrow()) {
                    var ready=result.scene().orElseThrow();check(ready.snapshot()==f.snapshot(),"retry preserves exact native snapshot");equal(12,ready.usage().faces(),"retry retains all original model faces");equal(state(407),ready.snapshot().states().getFirst(),"retry preserves original native state");equal(BIOME,ready.snapshot().biomes().getFirst(),"retry preserves original native biome");
                }
                close.run();noWorkerCharge(memory);
            }
        }
    }
    /** Account release can fail after removal; producer/construction errors must remain primary. */
    private static final class CaptureCloseFault implements AutoCloseable {
        final RenderMemory memory;final java.lang.reflect.Field field;final Set<RenderMemory.Account> original;final Error failure,secondary;int removals;
        CaptureCloseFault(RenderMemory memory,Error failure) { this(memory,failure,failure); }
        @SuppressWarnings("unchecked") CaptureCloseFault(RenderMemory memory,Error failure,Error secondary) {
            this.memory=memory;this.failure=failure;this.secondary=secondary;
            try {
                field=RenderMemory.class.getDeclaredField("owners");field.setAccessible(true);original=(Set<RenderMemory.Account>)field.get(memory);
                field.set(memory,new AbstractSet<RenderMemory.Account>() {
                    @Override public int size() { return original.size(); }
                    @Override public Iterator<RenderMemory.Account> iterator() { return original.iterator(); }
                    @Override public boolean add(RenderMemory.Account value) { return original.add(value); }
                    @Override public boolean remove(Object value) { removals++;original.remove(value);throw removals==1?CaptureCloseFault.this.failure:CaptureCloseFault.this.secondary; }
                });
            }catch(ReflectiveOperationException error) { throw new AssertionError("controlled capture-close fault unavailable",error); }
        }
        @Override public void close() { try { field.set(memory,original); }catch(ReflectiveOperationException error) { throw new AssertionError("capture-close fault restore failed",error); } }
    }
    private static void workerConstructionFailures() {
        for(boolean integrated:new boolean[]{false,true})for(boolean tracked:new boolean[]{false,true})for(int kind=0;kind<5;kind++)for(int cleanup=0;cleanup<3;cleanup++) {
            if(!tracked&&cleanup!=0||cleanup==2&&(kind!=1&&kind!=2))continue;
            try(var memory=new RenderMemory(new RenderMemory.Limits(100,2));var unrelated=memory.hold("unrelated",40)) {
                var before=memory.stats();var owners=memory.owners();int[] calls={0};RenderMemory destination=tracked?memory:null;
                Throwable primary=switch(kind) { case 0->new IllegalStateException("injected executor factory failure");case 1->new OutOfMemoryError("injected executor factory OOM");case 2->new AssertionError("injected executor factory error");default->null; };
                java.util.function.Supplier<ExecutorService> factory=kind==4?null:()->{
                    calls[0]++;equal(tracked?2:1,memory.stats().accounts(),"factory sees its cache admission and unrelated owner");
                    if(primary instanceof Error error)throw error;if(primary instanceof RuntimeException runtime)throw runtime;return null;
                };
                Runnable construct=()->{ if(integrated)new WorldRenderWorker(100,destination,factory);else new WorldSceneWorker(100,destination,factory); };
                Error release=cleanup==2?(Error)primary:new AssertionError("injected constructor account close error");Throwable actual;
                if(cleanup==0)actual=caughtFailure(construct);
                else try(var fault=new CaptureCloseFault(memory,release)) {
                    actual=caughtFailure(construct);equal(kind==4?0:1,fault.removals,"constructor attempts only its own admitted account release");
                }
                if(primary!=null)check(actual==primary,"constructor preserves exact executor factory failure");else check(actual instanceof NullPointerException,"null factory/executor rejected");
                int suppressed=cleanup==1&&kind!=4?1:0;equal(suppressed,actual.getSuppressed().length,"distinct constructor cleanup failure suppressed once; identical errors never self-suppress");if(suppressed!=0)check(actual.getSuppressed()[0]==release,"constructor release failure attached to original setup error");
                equal(kind==4?0:1,calls[0],"null supplier rejected before admission or executor invocation");equal(before,memory.stats(),"failed constructor preserves exact unrelated admission");equal(owners,memory.owners(),"failed constructor leaves no retired/empty owner");equal(40L,unrelated.bytes(),"unrelated hold remains usable");
                try(var executor=new DrainExecutor()) {
                    AutoCloseable retry=integrated?new WorldRenderWorker(100,destination,()->executor):new WorldSceneWorker(100,destination,()->executor);
                    equal(tracked?2:1,memory.stats().accounts(),"retry reuses failed constructor account slot");equal(0,executor.attempts,"construction schedules no native or scene work");
                    try { retry.close(); }catch(Exception failure) { throw new AssertionError("constructor retry close failed",failure); }
                    check(executor.isShutdown(),"retry owns and shuts down its supplied executor");equal(before,memory.stats(),"retry releases only its own empty cache account");
                }
            }
        }
        // With no account slots available, a null supplier still fails metadata validation first.
        try(var memory=new RenderMemory(new RenderMemory.Limits(100,1));var occupied=memory.hold("occupied",40)) {
            throwsType(NullPointerException.class,()->new WorldSceneWorker(100,memory,null),"null scene factory precedes account-limit admission");throwsType(NullPointerException.class,()->new WorldRenderWorker(100,memory,null),"null integrated factory precedes account-limit admission");equal(40L,occupied.bytes(),"invalid factory preserves full coordinator");equal(1,memory.stats().accounts(),"invalid factory never registers an account");
        }
    }
    private static void captureCleanupFailures() {
        var f=fixture(2,255,false);try(var cache=new WorldSectionCache();var owner=build(f,cache)) {
            for(boolean lighting:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var fault=new CaptureCloseFault(memory,new AssertionError("injected capture hold release error"))) {
                var primary=new AssertionError("injected capture producer error");Throwable actual;
                if(lighting) {
                    var scene=owner.scene().orElseThrow();var reader=new WorldLighting.Reader() {
                        @Override public void begin() { throw primary; }
                        @Override public int sections() { return scene.sections().size(); }
                        @Override public int components(int section) { return scene.sections().get(section).faces().size()*12; }
                        @Override public float component(int section,int face,int vertex,int channel) { return 1; }
                    };
                    actual=caughtFailure(()->WorldLighting.readOwned(scene,"synthetic capture error",reader,WorldLighting.Limits.DEFAULT,memory,LIVE));
                }else {
                    int[] checks={0};actual=caughtFailure(()->ClientColormaps.readOwned(f.assets(),ClientColormaps.Limits.DEFAULT,memory,()->{ if(++checks[0]==2)throw primary;return false; }));
                }
                check(actual==primary,"owned capture preserves exact source error through hold cleanup");check(primary.getSuppressed().length==1&&primary.getSuppressed()[0]==fault.failure,"capture release error suppressed onto source error");noWorkerCharge(memory);check(!owner.closed()&&owner.scene().orElseThrow().snapshot()==f.snapshot(),"capture failure preserves independently owned native scene");
            }
        }
    }
    private static void ownedCleanupFailures() {
        sceneCompletionCleanupFailures();
        captureCleanupFailures();
        var f=fixture(2,128,false);
        for(boolean indexing:new boolean[]{false,true})for(boolean ready:new boolean[]{false,true}) {
            try(var cache=new WorldSectionCache();var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new DrainExecutor()) {
                var source=build(f,cache);var cleanup=new AssertionError("injected worker source release error");
                if(indexing)try(var worker=new WorldPickIndexWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(source);if(ready)executor.runNext();check(memory.stats().chargedBytes()>0,"index worker owns admitted source before cleanup");
                    try(var fault=new TicketFault(source,cleanup)) { check(caughtFailure(worker::invalidate)==cleanup,"index invalidation preserves exact cleanup error");fault.allVisited();check(source.closed(),"index invalidation consumes its source wrapper");equal(0L,worker.reservedBytes(),"failed index cleanup clears completed/pending reservation");equal(0L,memory.stats().chargedBytes(),"index cleanup still releases shared hold");check(worker.take(revision).isEmpty(),"closed index completion cannot escape after invalidation failure"); }
                    equal(0L,cache.stats().leases(),"test restores and releases deliberately faulted source ticket");var retry=build(f,cache);equal(revision+1,worker.submit(retry),"index cleanup failure does not spend an invalidation revision");worker.close();check(retry.closed(),"index retry source closes");noWorkerCharge(memory);
                }else try(var worker=new WorldOrderWorker(4*1024*1024,memory,()->executor)) {
                    long revision=worker.submit(source,orderView(f));if(ready)executor.runNext();check(memory.stats().chargedBytes()>0,"sort worker owns admitted source before cleanup");
                    try(var fault=new TicketFault(source,cleanup)) { check(caughtFailure(worker::invalidate)==cleanup,"sort invalidation preserves exact cleanup error");fault.allVisited();check(source.closed(),"sort invalidation consumes its source wrapper");equal(0L,worker.reservedBytes(),"failed sort cleanup clears completed/pending reservation");equal(0L,memory.stats().chargedBytes(),"sort cleanup still releases shared hold");check(worker.take(revision).isEmpty(),"closed sort completion cannot escape after invalidation failure"); }
                    equal(0L,cache.stats().leases(),"test restores and releases deliberately faulted sort ticket");var retry=build(f,cache);equal(revision+1,worker.submit(retry,orderView(f)),"sort cleanup failure does not spend an invalidation revision");worker.close();check(retry.closed(),"sort retry source closes");noWorkerCharge(memory);
                }
            }
        }
        for(boolean indexing:new boolean[]{false,true})try(var cache=new WorldSectionCache();var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var executor=new DrainExecutor();var source=build(f,cache);var fault=new TicketFault(source,new AssertionError("injected rejected scene release"))) {
            Throwable actual;
            if(indexing)try(var worker=new WorldPickIndexWorker(4*1024*1024,memory,()->executor)) { actual=caughtFailure(()->worker.submit(source,null));equal(0L,worker.status().revision(),"index preflight rejection does not spend revision"); }
            else try(var worker=new WorldOrderWorker(4*1024*1024,memory,()->executor)) { actual=caughtFailure(()->worker.submit(source,null));equal(0L,worker.status().revision(),"sort preflight rejection does not spend revision"); }
            check(actual instanceof NullPointerException,"preflight error remains primary through source cleanup");check(Arrays.stream(actual.getSuppressed()).anyMatch(value->value==fault.failure),"rejected source cleanup is suppressed onto original validation error");fault.allVisited();check(source.closed(),"rejected fresh source consumed");noWorkerCharge(memory);
        }
        try(var cache=new WorldSectionCache();var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var source=build(f,cache);var fault=new TicketFault(source,new AssertionError("injected prepare source release"))) {
            var primary=new AssertionError("injected admitted index preparation error");int[] checks={0};
            var actual=caughtFailure(()->WorldPickIndex.prepare(source,WorldPickIndex.Limits.DEFAULT,memory,()->{ if(++checks[0]==4)throw primary;return false; }));
            check(actual==primary,"admitted index preparation retains exact primary error");check(Arrays.stream(primary.getSuppressed()).anyMatch(value->value==fault.failure),"prepare source-release error suppressed");fault.allVisited();check(source.closed(),"failed preparation consumes source wrapper");noWorkerCharge(memory);
        }
        try(var cache=new WorldSectionCache();var memory=new RenderMemory(new RenderMemory.Limits(8*1024*1024,4));var source=build(f,cache);var index=WorldPickIndex.prepare(source,WorldPickIndex.Limits.DEFAULT,memory,LIVE);var fault=new TicketFault(source,new AssertionError("injected deferred index source release"))) {
            var primary=new AssertionError("injected active query error");int[] checks={0};
            var actual=caughtFailure(()->index.query(ray(f.snapshot()),()->{ if(++checks[0]==2) { index.close();throw primary; }return false; }));
            check(actual==primary,"query error remains primary through deferred close");check(Arrays.stream(primary.getSuppressed()).anyMatch(value->value==fault.failure),"deferred source-release error suppressed");fault.allVisited();check(index.closed()&&index.released()&&source.closed(),"last query detaches closed index storage and source despite release error");equal(0L,index.retainedBytes(),"closed index arrays released");noWorkerCharge(memory);index.close();
        }
    }
    private static void indexOwnership() {
        ownedCleanupFailures();
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
        group("bounded exact native biome blending",WorldCpuContracts::biomeBlending);
        group("blended fluid materials and native provenance",WorldCpuContracts::fluidBlendMaterials);
        group("detached bounded visual limitation reports",WorldCpuContracts::visualReports);
        group("background visual report ownership and recovery",WorldCpuContracts::reportWorkers);
        group("model cube winding and provenance",WorldCpuContracts::geometry);
        group("atlas extrusion and exact binding",WorldCpuContracts::atlas);
        group("native visual assignment guards",WorldCpuContracts::assignments);
        group("explicit native visibility masks",WorldCpuContracts::visibility);
        group("complete world mesh metadata",WorldCpuContracts::meshReceipts);
        group("complete scene limits and identity",WorldCpuContracts::completeLimits);
        group("shared scene and cache ownership",WorldCpuContracts::cacheOwners);
        group("pinned cache replacement admission",WorldCpuContracts::pinnedReplacement);
        group("world geometry picking and alpha",WorldCpuContracts::picking);
        group("conservative picking hierarchy boundaries",WorldCpuContracts::pickingTraversalEdges);
        group("independent translucent centroid ordering",WorldCpuContracts::ordering);
        group("pending sort replacement admission",WorldCpuContracts::pendingOrderAdmission);
        group("translucent view preflight preservation",WorldCpuContracts::orderViewPreflight);
        group("ready sort replacement admission",WorldCpuContracts::readyOrderAdmission);
        group("transferred sort ownership and admission",WorldCpuContracts::transferredOrderAdmission);
        group("active sort cancellation and admission",WorldCpuContracts::activeOrderAdmission);
        group("scene-worker shutdown recovery",WorldCpuContracts::workerShutdownRecovery);
        group("worker startup drain and remaining shutdown recovery",WorldCpuContracts::remainingWorkerShutdownRecovery);
        group("explicit empty and unsupported markers",WorldCpuContracts::emptyAndMarkers);
        group("picking-index admission and source release",WorldCpuContracts::indexOwnership);
        System.out.println("PASS "+groups+" synthetic world contract groups; native/GPU parity is outside this harness.");
    }
}
