package dev.irisvisualizer.renderer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Test-only detached worlds/assets. No selected client, native generator or preview readiness is attested. */
public final class WorldGpuFixture implements AutoCloseable {
    private static final BooleanSupplier LIVE=()->false;
    public static final String LIGHTING="synthetic uniform final RGB; no native light provider";
    private record Material(int rgba,boolean tinted,boolean animated,boolean interpolate,boolean cutout) {}
    private final WorldSectionCache cache;private final WorldScene.Result owner;
    private WorldGpuFixture(WorldSectionCache cache,WorldScene.Result owner) { this.cache=cache;this.owner=owner; }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    public WorldScene scene() { return owner.scene().orElseThrow(); }
    public WorldScene.Result retain() { return owner.retain(); }
    public static WorldGpuFixture solid(int rgba,boolean tinted) { return build(List.of(new Material(rgba,tinted,false,false,false)),new int[]{0},1,1,WorldModels.Kind.MESH); }
    public static WorldGpuFixture neighbors() { return build(List.of(new Material(0xffffffff,false,false,false,false)),new int[]{0,0},2,1,WorldModels.Kind.MESH); }
    public static WorldGpuFixture cutout() { return build(List.of(new Material(0x00ff00ff,false,false,false,true)),new int[]{0},1,1,WorldModels.Kind.MESH); }
    public static WorldGpuFixture translucent() { return build(List.of(new Material(0x0000ff80,false,false,false,false),new Material(0xff000080,false,false,false,false)),new int[]{0,1},1,2,WorldModels.Kind.MESH); }
    public static WorldGpuFixture culling(boolean mixed) {
        var materials=mixed?List.of(new Material(0x60a0e080,false,false,false,false),new Material(0xd0a060ff,false,false,false,false)):List.of(new Material(0x60a0e080,false,false,false,false));
        int[] assignments=new int[32*32];if(mixed)for(int z=0;z<32;z++)Arrays.fill(assignments,z*32,(z+1)*32,z&1);
        return build(materials,assignments,32,32,WorldModels.Kind.MESH);
    }
    public static WorldGpuFixture animated(boolean interpolate) { return build(List.of(new Material(0xff0000ff,false,true,interpolate,false)),new int[]{0},1,1,WorldModels.Kind.MESH); }
    public static WorldGpuFixture empty() { return build(List.of(new Material(0xffffffff,false,false,false,false)),new int[]{0},1,1,WorldModels.Kind.EMPTY); }
    public static WorldGpuFixture placeholder() { return build(List.of(new Material(0xffffffff,false,false,false,false)),new int[]{0},1,1,WorldModels.Kind.PLACEHOLDER); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void asset(Map<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset> assets,ClientAssetSnapshot.Kind kind,AssetId id,byte[] bytes) {
        assets.put(new ClientAssetSnapshot.Key(kind,id),ClientAssetSnapshot.Asset.owned("synthetic-gpu/"+kind+"/"+id,bytes,hash(bytes)));
    }
    private static void chunk(DataOutputStream out,String name,byte[] bytes) throws IOException {
        byte[] type=name.getBytes(StandardCharsets.US_ASCII);var crc=new CRC32();crc.update(type);crc.update(bytes);out.writeInt(bytes.length);out.write(type);out.write(bytes);out.writeInt((int)crc.getValue());
    }
    private static byte[] texture(Material material) {
        int width=material.cutout()?2:1,height=material.animated()?2:1;
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeLong(0x89504e470d0a1a0aL);var headerBytes=new ByteArrayOutputStream();var header=new DataOutputStream(headerBytes);header.writeInt(width);header.writeInt(height);header.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",headerBytes.toByteArray());
            var compressed=new ByteArrayOutputStream();try(var zlib=new DeflaterOutputStream(compressed)) {
                for(int y=0;y<height;y++) { zlib.write(0);for(int x=0;x<width;x++) { int rgba=material.cutout()&&x==0?0xff000000:material.animated()&&y==1?0x0000ffff:material.rgba();for(int shift=24;shift>=0;shift-=8)zlib.write(rgba>>>shift&255); } }
            }
            chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return bytes.toByteArray();
        }catch(IOException impossible) { throw new AssertionError(impossible); }
    }
    private static WorldGpuFixture build(List<Material> materials,int[] assignments,int width,int depth,WorldModels.Kind kind) {
        var assets=new TreeMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset>();var states=new ArrayList<RenderBlockState>();
        for(int i=0;i<materials.size();i++) {
            var block=new AssetId("fixture","gpu_cube_"+i);var model=new AssetId("fixture","block/gpu_cube_"+i);var sprite=new AssetId("fixture","block/gpu_pixel_"+i);var material=materials.get(i);
            states.add(new RenderBlockState(407+i*508,block+"[original_flag=kept]",block,Map.of("original_flag","kept"),Map.of("original_flag",Set.of("kept","other"))));
            asset(assets,ClientAssetSnapshot.Kind.BLOCKSTATE,block,("{\"variants\":{\"\":{\"model\":\""+model+"\"}}}").getBytes(StandardCharsets.UTF_8));
            var faces=new StringJoiner(",");for(var direction:ModelPlan.Direction.values()) { var name=direction.name().toLowerCase(Locale.ROOT);faces.add("\""+name+"\":{\"texture\":\"#all\",\"cullface\":\""+name+"\""+(material.tinted()?",\"tintindex\":0":"")+"}"); }
            asset(assets,ClientAssetSnapshot.Kind.MODEL,model,("{\"textures\":{\"all\":\""+sprite+"\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"+faces+"}}]}").getBytes(StandardCharsets.UTF_8));asset(assets,ClientAssetSnapshot.Kind.TEXTURE,sprite,texture(material));
            if(material.animated())asset(assets,ClientAssetSnapshot.Kind.TEXTURE_METADATA,sprite,("{\"animation\":{\"frametime\":2,\"interpolate\":"+material.interpolate()+",\"frames\":[0,1]}}").getBytes(StandardCharsets.UTF_8));
        }
        long bytes=assets.values().stream().mapToLong(ClientAssetSnapshot.Asset::bytes).sum();var receipt=new ClientAssetSnapshot.Receipt(Path.of("synthetic-world-gpu-fixture"),hash(materials.toString().getBytes(StandardCharsets.UTF_8)),bytes,BlockModels.PROFILE,BlockModels.PROFILE,assets.size(),0,bytes);var catalog=new ClientAssetSnapshot(receipt,assets);
        var biome=new WorldSnapshot.Biome(731,"fixture:original_gpu_biome",new AssetId("fixture","original_gpu_biome"));var generation=new WorldSnapshot.Source(UUID.randomUUID(),"0".repeat(64),"synthetic world GPU fixture",BlockModels.PROFILE,AssetId.minecraft("overworld"),Long.MAX_VALUE);
        var snapshot=WorldSnapshot.capture(generation,new WorldSnapshot.Extent(9_007_199_254_740_993L,-64,-9_007_199_254_740_993L,width,1,depth),states,List.of(biome),assignments,new int[assignments.length],LIVE);check(!snapshot.requiresPlaceholder(),"complete synthetic original state/biome capture");
        AtlasLayout layout;var entries=new ArrayList<WorldModels.Entry>();
        if(kind==WorldModels.Kind.MESH) {
            var textures=new ArrayList<TexturePreparation>();for(var state:states) { var model=BlockModels.resolve(catalog,state,LIVE);check(!model.requiresPlaceholder(),"synthetic world JSON model resolved: "+model.problems());var mesh=GeometryBaker.bake(model,new int[]{0},LIVE);check(!mesh.requiresPlaceholder(),"synthetic world model baked");var prepared=TextureMaterials.prepare(catalog,mesh,LIVE);check(!prepared.requiresPlaceholder(),"synthetic world texture prepared: "+prepared.problems());textures.add(prepared); }
            layout=AtlasBuilder.plan(textures,new AtlasBuilder.Limits(8,8,64,64,8,4,1,4096,16384,4096,16384,4096,1024*1024,4096),LIVE);check(!layout.requiresPlaceholder(),"synthetic world shared atlas planned: "+layout.problems());
            for(var texture:textures) { var bound=AtlasMeshes.bind(layout,texture,LIVE);check(!bound.requiresPlaceholder(),"synthetic world mesh bound");entries.add(WorldModels.Entry.from(bound)); }
        }else {
            layout=AtlasBuilder.empty(catalog,states.getFirst().block(),LIVE);
            entries.add(kind==WorldModels.Kind.EMPTY?WorldModels.Entry.empty(states.getFirst()):WorldModels.Entry.placeholder(states.getFirst(),List.of(new ModelPlan.Problem(ModelPlan.Code.UNSUPPORTED_GEOMETRY,ClientAssetSnapshot.Kind.BLOCKSTATE,states.getFirst().block(),"/synthetic","Synthetic unsupported visual; original state retained"))));
        }
        var models=WorldModels.capture(snapshot,layout,entries,assignments,LIVE);check(!models.requiresPlaceholder(),"complete synthetic world assignments: "+models.problems());var masks=new byte[assignments.length];
        if(width==2&&depth==1) { masks[0]=(byte)(1<<ModelPlan.Direction.EAST.ordinal());masks[1]=(byte)(1<<ModelPlan.Direction.WEST.ordinal()); }
        var visibility=WorldVisibility.capture(snapshot,layout,"explicit synthetic neighbor masks",masks,LIVE);var plan=WorldScenePlan.plan(models,visibility,new WorldScenePlan.Limits(width>2||depth>2?32:2,32,8192,2048,64,2*1024*1024,8*1024*1024),WorldMesher.Limits.DEFAULT,LIVE);check(!plan.requiresPlaceholder(),"complete synthetic world plan: "+plan.problems());var raster=AtlasImages.capture(layout,0,0,LIVE);check(!raster.requiresPlaceholder(),"complete synthetic atlas raster");
        var cache=new WorldSectionCache();try { var owner=WorldScene.build(plan,raster,cache,LIVE);if(owner.requiresPlaceholder()) { var problems=owner.problems();owner.close();throw new AssertionError("synthetic CPU world build failed: "+problems); }return new WorldGpuFixture(cache,owner); }catch(RuntimeException|Error failure) { try { cache.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    public WorldLighting lighting(float red,float green,float blue) {
        var scene=scene();var samples=new ArrayList<float[]>();for(var mesh:scene.sections()) { var values=new float[mesh.faces().size()*12];for(int i=0;i<values.length;i+=3) { values[i]=red;values[i+1]=green;values[i+2]=blue; }samples.add(values); }
        var lighting=WorldLighting.capture(scene,LIGHTING,samples,LIVE);check(!lighting.requiresPlaceholder(),"synthetic uniform lighting capture: "+lighting.problems());return lighting;
    }
    public WorldTint tint(int rgb) {
        var samples=new ArrayList<int[]>();for(var mesh:scene().sections()) { var values=new int[mesh.faces().size()*4];for(int face=0;face<mesh.faces().size();face++)Arrays.fill(values,face*4,face*4+4,mesh.faces().get(face).source().source().tintIndex()>=0?rgb:0xffffff);samples.add(values); }
        var tint=WorldTint.capture(scene(),"synthetic constant tint; no native provider",samples,LIVE);check(!tint.requiresPlaceholder(),"synthetic tint capture");return tint;
    }
    @Override public void close() { try(owner) { cache.close(); } }
}
