package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Worker-side, snapshot-bound texture decoding and conservative source-alpha classification. */
public final class TextureMaterials {
    private TextureMaterials() {}
    public record Limits(int sprites,int faces,int imageDimension,long imagePixels,long rgbaBytes,long encodedBytes,
                         int metadataBytes,int metadataNodes,int frames,int regions,long alphaPixelVisits) {
        public static final Limits DEFAULT=new Limits(256,24576,8192,16777216,128L*1024*1024,32L*1024*1024,1024*1024,65536,16384,8192,64L*1024*1024);
        public Limits {
            if(sprites<1||sprites>256||faces<1||faces>24576||imageDimension<1||imageDimension>8192||imagePixels<1||imagePixels>16777216||rgbaBytes<0||rgbaBytes>128L*1024*1024||encodedBytes<0||encodedBytes>32L*1024*1024||metadataBytes<0||metadataBytes>1024*1024||metadataNodes<1||metadataNodes>65536||frames<1||frames>16384||regions<1||regions>8192||alphaPixelVisits<0||alphaPixelVisits>64L*1024*1024)
                throw new IllegalArgumentException("Texture limits exceed renderer bounds");
        }
    }
    private record ScanKey(AssetId sprite,TexturePreparation.Region region,boolean fullImage) {
        ScanKey(AssetId sprite,TexturePreparation.Region region) { this(sprite,region,false); }
    }
    private static List<AssetId> requiredSprites(ModelPlan.Material material) { return material.alphaSprites().isEmpty()?List.of(material.sprite()):material.alphaSprites(); }
    private static ScanKey sheetKey(TextureSprite sprite) { return new ScanKey(sprite.id(),new TexturePreparation.Region(0,0,sprite.image().width(),sprite.image().height()),true); }
    static TextureBatch prepareBatch(ClientAssetSnapshot assets,List<BlockMesh> meshes,TextureBatch.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(meshes);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);AtlasBuilder.check(cancelled);
        try { return executeBatch(assets,meshes,limits,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return TextureBatch.failed(List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return TextureBatch.failed(List.of(AtlasBuilder.blockFailure(ModelPlan.Code.MEMORY,meshes.isEmpty()?AssetId.minecraft("air"):meshes.getFirst().state().block(),"Not enough memory to prepare shared textures; no partial batch was published").problem)); }
    }
    private static TextureBatch executeBatch(ClientAssetSnapshot assets,List<BlockMesh> inputs,TextureBatch.Limits limits,BooleanSupplier cancelled) {
        AssetId owner=inputs.isEmpty()?AssetId.minecraft("air"):Objects.requireNonNull(inputs.getFirst()).state().block();
        if(inputs.size()>limits.meshes())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,owner,"Shared texture mesh count exceeded");
        if(!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw AtlasBuilder.blockFailure(ModelPlan.Code.VERSION,owner,"Shared textures require the pinned selected-client profile");
        var meshes=List.copyOf(inputs);var ids=new TreeSet<AssetId>();var packets=new IdentityHashMap<MeshPacket,Boolean>();long packed=0;int faces=0;
        for(var mesh:meshes) {
            AtlasBuilder.check(cancelled);if(!AtlasLayout.sameAssets(assets.receipt(),mesh.assets()))throw AtlasBuilder.blockFailure(ModelPlan.Code.VERSION,mesh.state().block(),"Shared texture meshes must use the exact selected asset capture");
            if(mesh.requiresPlaceholder()) { for(var problem:mesh.problems())if(TextureBatch.fatal(problem.code()))throw new ModelFields.Failure(problem);continue; }
            var packet=mesh.packet().orElseThrow();if(packets.put(packet,Boolean.TRUE)==null)packed+=packet.packedBytes();faces+=mesh.quads().size();
            if(packed>limits.sourcePackedBytes()||faces>limits.faces()||assets.receipt().capturedAssetBytes()+packed>limits.workingBytes())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,mesh.state().block(),"Shared texture face/source/working budget exceeded");
            var localIds=new HashSet<AssetId>();if(mesh.quads().size()>limits.resources().faces())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,owner,"Per-mesh texture face limit exceeded");
            for(var quad:mesh.quads())for(var id:requiredSprites(quad.material())) { AtlasBuilder.check(cancelled);localIds.add(id);if(localIds.size()>limits.resources().sprites())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,owner,"Per-mesh texture sprite/dependency limit exceeded");ids.add(id);if(ids.size()>limits.sprites())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,owner,"Shared sprite/dependency count exceeded"); }
        }
        long remaining=limits.workingBytes()-assets.receipt().capturedAssetBytes()-packed;
        if(remaining<0)throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,owner,"Captured assets and source packets exceed shared texture working budget");
        var source=limits.resources();var bounded=new Limits(source.sprites(),source.faces(),source.imageDimension(),source.imagePixels(),Math.min(source.rgbaBytes(),remaining),source.encodedBytes(),source.metadataBytes(),source.metadataNodes(),source.frames(),source.regions(),source.alphaPixelVisits());
        var work=new Work(assets,bounded,cancelled);var failed=new TreeMap<AssetId,ModelPlan.Problem>();
        var nodesBySprite=new HashMap<AssetId,Integer>();
        for(var id:ids) { work.check();try { int before=work.metadataNodes;work.load(id);nodesBySprite.put(id,work.metadataNodes-before); }catch(ModelFields.Failure failure) { if(TextureBatch.fatal(failure.problem.code()))throw failure;failed.put(id,failure.problem); } }
        var results=new ArrayList<TexturePreparation>(meshes.size());
        for(var mesh:meshes) {
            work.check();if(mesh.requiresPlaceholder()) { results.add(TexturePreparation.placeholder(mesh,mesh.problems()));continue; }
            var sprites=new TreeMap<AssetId,TextureSprite>();var seen=new HashSet<AssetId>();var problems=new ArrayList<ModelPlan.Problem>();
            for(var quad:mesh.quads())for(var id:requiredSprites(quad.material())) { work.check();if(!seen.add(id))continue;var problem=failed.get(id);if(problem!=null) { if(!problems.contains(problem))problems.add(problem); }else sprites.put(id,work.sprites.get(id)); }
            if(!problems.isEmpty()) { results.add(TexturePreparation.placeholder(mesh,problems));continue; }
            long encoded=0,rgba=0;int metadata=0,nodes=0,frames=0;
            for(var entry:sprites.entrySet()) { work.check();var id=entry.getKey();var sprite=entry.getValue();var png=assets.find(ClientAssetSnapshot.Kind.TEXTURE,id).orElseThrow();int meta=assets.find(ClientAssetSnapshot.Kind.TEXTURE_METADATA,id).map(ClientAssetSnapshot.Asset::bytes).orElse(0);encoded+=(long)png.bytes()+meta;metadata+=meta;nodes+=nodesBySprite.get(id);rgba+=sprite.image().bytes();frames+=sprite.frames().size(); }
            try {
            var prepared=new ArrayList<TexturePreparation.Face>(mesh.quads().size());var regions=new HashSet<ScanKey>();long visits=0;
            for(int face=0;face<mesh.quads().size();face++) { work.check();prepared.add(work.face(face,mesh.quads().get(face),regions)); }
            for(var key:regions) { work.check();var region=key.region();visits+=(long)(region.x1()-region.x0())*(region.y1()-region.y0())*(key.fullImage()?1:work.sprites.get(key.sprite()).referencedFrames().size()); }
            results.add(TexturePreparation.success(mesh,sprites,prepared,new TexturePreparation.Usage(sprites.size(),encoded,rgba,metadata,nodes,frames,regions.size(),visits)));
            }catch(ModelFields.Failure failure) { if(TextureBatch.fatal(failure.problem.code()))throw failure;results.add(TexturePreparation.placeholder(mesh,List.of(failure.problem))); }
        }
        long retained=0;var exported=new IdentityHashMap<RgbaImage,Boolean>();for(var result:results)for(var sprite:result.sprites().values())if(exported.put(sprite.image(),Boolean.TRUE)==null)retained+=sprite.image().bytes();work.check();
        return TextureBatch.success(results,new TextureBatch.Usage(meshes.size(),work.sprites.size(),failed.size(),packed,retained,work.rgbaBytes,work.encodedBytes,work.metadataBytes,work.metadataNodes,work.frames,work.scans.size(),work.alphaPixelVisits));
    }
    private static final class Work {
        final ClientAssetSnapshot assets;final Limits limits;final BooleanSupplier cancelled;
        final NavigableMap<AssetId,TextureSprite> sprites=new TreeMap<>();final Map<ScanKey,TexturePreparation.Alpha> scans=new HashMap<>();final Map<List<AssetId>,TexturePreparation.Alpha> groups=new HashMap<>();
        long encodedBytes,rgbaBytes,alphaPixelVisits;int metadataBytes,metadataNodes,frames;
        Work(ClientAssetSnapshot assets,Limits limits,BooleanSupplier cancelled) { this.assets=assets;this.limits=limits;this.cancelled=cancelled; }
        void check() { if(cancelled.getAsBoolean())throw new CancellationException("Texture preparation cancelled"); }
        void require(boolean condition,ModelPlan.Code code,ClientAssetSnapshot.Kind kind,AssetId id,String pointer,String reason) { check();if(!condition)throw TextureMetadata.failure(code,kind,id,pointer,reason); }
        void load(AssetId id) {
            check();var png=assets.find(ClientAssetSnapshot.Kind.TEXTURE,id).orElseThrow(()->TextureMetadata.failure(ModelPlan.Code.MISSING_ASSET,ClientAssetSnapshot.Kind.TEXTURE,id,"","Referenced PNG was not captured"));
            var metadata=assets.find(ClientAssetSnapshot.Kind.TEXTURE_METADATA,id);
            int metadataSize=metadata.map(ClientAssetSnapshot.Asset::bytes).orElse(0);long encoded=(long)png.bytes()+metadataSize;
            require(encoded<=limits.encodedBytes()-encodedBytes,ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,id,"","Aggregate encoded texture byte budget reached");
            require(metadataSize<=limits.metadataBytes()-metadataBytes,ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE_METADATA,id,"","Aggregate texture metadata byte budget reached");
            encodedBytes+=encoded;metadataBytes+=metadataSize;
            var image=PngDecoder.decode(id,png.copyBytes(),limits,limits.rgbaBytes()-rgbaBytes,cancelled);rgbaBytes+=image.bytes();check();
            var description=TextureMetadata.read(id,metadata.map(ClientAssetSnapshot.Asset::copyBytes).orElse(null),image,limits.metadataNodes()-metadataNodes,limits.frames()-frames,cancelled);
            metadataNodes+=description.nodes();frames+=description.frames().size();
            var sprite=new TextureSprite(id,png.sha256(),metadata.map(ClientAssetSnapshot.Asset::sha256),image,description);check();sprites.put(id,sprite);
        }
        TexturePreparation.Region region(BlockMesh.Quad quad,TextureSprite sprite) {
            float minU=1,minV=1,maxU=0,maxV=0;
            for(var vertex:quad.vertices()) {
                require(vertex.u()>=0&&vertex.u()<=1&&vertex.v()>=0&&vertex.v()<=1,ModelPlan.Code.UV_RANGE,quad.source().kind(),quad.source().asset(),quad.source().pointer(),"Texture classification requires finite sprite-local UVs in 0..1");
                minU=Math.min(minU,vertex.u());minV=Math.min(minV,vertex.v());maxU=Math.max(maxU,vertex.u());maxV=Math.max(maxV,vertex.v());
            }
            int width=sprite.frameWidth(),height=sprite.frameHeight();
            // Include the pixel at each boundary, then clamp UV 1 to the last pixel. This is conservative,
            // not a claim about native UV epsilon, bilinear filtering, mipmaps or atlas gutters.
            return new TexturePreparation.Region(Math.min(width-1,(int)StrictMath.floor((double)minU*width)),Math.min(height-1,(int)StrictMath.floor((double)minV*height)),
                Math.min(width-1,(int)StrictMath.floor((double)maxU*width))+1,Math.min(height-1,(int)StrictMath.floor((double)maxV*height))+1);
        }
        TexturePreparation.Face face(int index,BlockMesh.Quad quad,Set<ScanKey> used) {
            var material=quad.material();var sprite=sprites.get(material.sprite());var region=region(quad,sprite);var mapped=alpha(sprite,region);used.add(new ScanKey(sprite.id(),region));
            Optional<TexturePreparation.Alpha> whole=Optional.empty();
            if(!material.alphaSprites().isEmpty()) {
                whole=Optional.of(group(material.alphaSprites()));for(var id:material.alphaSprites()) { check();used.add(sheetKey(sprites.get(id))); }
            }
            return new TexturePreparation.Face(index,quad.source(),material,region,mapped,whole.orElse(mapped).classification(material.forceTranslucent()),whole);
        }
        TexturePreparation.Alpha group(List<AssetId> ids) {
            check();var cached=groups.get(ids);if(cached!=null)return cached;
            require(groups.size()<limits.regions(),ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,ids.getFirst(),"","Material alpha-group metadata budget reached");
            TexturePreparation.Alpha result=null;
            for(var id:ids) { check();var alpha=sheet(sprites.get(id));result=result==null?alpha:result.or(alpha); }
            groups.put(ids,result);return result;
        }
        TexturePreparation.Alpha sheet(TextureSprite sprite) {
            check();var key=sheetKey(sprite);var cached=scans.get(key);if(cached!=null)return cached;
            require(scans.size()<limits.regions(),ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,sprite.id(),"","Combined mapped/sheet alpha-region budget reached");
            var image=sprite.image();long visits=(long)image.width()*image.height();
            require(visits<=limits.alphaPixelVisits()-alphaPixelVisits,ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,sprite.id(),"","Whole-sheet alpha pixel-visit budget reached");alphaPixelVisits+=visits;
            boolean transparent=false,partial=false,opaque=false;
            for(int y=0;y<image.height();y++) { check();for(int x=0;x<image.width();x++) { if((x&1023)==0)check();int alpha=image.alpha(x,y);transparent|=alpha==0;opaque|=alpha==255;partial|=alpha>0&&alpha<255; } }
            var result=new TexturePreparation.Alpha(transparent,partial,opaque,false);scans.put(key,result);return result;
        }
        TexturePreparation.Alpha alpha(TextureSprite sprite,TexturePreparation.Region region) {
            check();var key=new ScanKey(sprite.id(),region);var cached=scans.get(key);if(cached!=null)return cached;
            require(scans.size()<limits.regions(),ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,sprite.id(),"","Unique UV alpha-region budget reached");
            long visits=(long)(region.x1()-region.x0())*(region.y1()-region.y0())*sprite.referencedFrames().size();
            require(visits<=limits.alphaPixelVisits()-alphaPixelVisits,ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,sprite.id(),"","Animation-aware alpha pixel-visit budget reached");alphaPixelVisits+=visits;
            boolean transparent=false,partial=false,opaque=false;var image=sprite.image();
            for(int frame:sprite.referencedFrames()) {
                check();var tile=sprite.tile(frame);
                for(int y=region.y0();y<region.y1();y++) {
                    check();for(int x=region.x0();x<region.x1();x++) {
                        if((x&1023)==0)check();int alpha=image.alpha(tile.x()+x,tile.y()+y);transparent|=alpha==0;opaque|=alpha==255;partial|=alpha>0&&alpha<255;
                    }
                }
            }
            // RGBA interpolation can blend binary alpha. This conservative summary includes
            // spatially separate masks; it does not claim that every transition blends alpha.
            boolean blend=sprite.interpolate()&&sprite.referencedFrames().size()>1&&transparent&&opaque;
            var result=new TexturePreparation.Alpha(transparent,partial,opaque,blend);scans.put(key,result);return result;
        }
    }
    public static TexturePreparation prepare(ClientAssetSnapshot assets,BlockMesh mesh,BooleanSupplier cancelled) { return prepare(assets,mesh,Limits.DEFAULT,cancelled); }
    public static TexturePreparation prepare(ClientAssetSnapshot assets,BlockMesh mesh,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(mesh);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        if(cancelled.getAsBoolean())throw new CancellationException("Texture preparation cancelled");
        try { return execute(assets,mesh,limits,cancelled); }
        catch(ModelFields.Failure failure) { if(cancelled.getAsBoolean())throw new CancellationException("Texture preparation cancelled");return TexturePreparation.placeholder(mesh,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) {
            if(cancelled.getAsBoolean())throw new CancellationException("Texture preparation cancelled");
            return TexturePreparation.placeholder(mesh,List.of(new ModelPlan.Problem(ModelPlan.Code.MEMORY,ClientAssetSnapshot.Kind.BLOCKSTATE,mesh.state().block(),"","Not enough memory to prepare textures; no partial texture bank was published")));
        }
    }
    private static TexturePreparation execute(ClientAssetSnapshot assets,BlockMesh mesh,Limits limits,BooleanSupplier cancelled) {
        if(mesh.requiresPlaceholder())return TexturePreparation.placeholder(mesh,mesh.problems());
        var work=new Work(assets,limits,cancelled);var owner=mesh.state().block();var receipt=assets.receipt();var original=mesh.assets();
        work.require(receipt.archiveSha256().equals(original.archiveSha256())&&receipt.version().equals(original.version())&&receipt.expectedVersion().equals(original.expectedVersion())&&receipt.version().equals(BlockModels.PROFILE)&&receipt.expectedVersion().equals(BlockModels.PROFILE),
            ModelPlan.Code.VERSION,ClientAssetSnapshot.Kind.BLOCKSTATE,owner,"","Texture preparation requires the mesh's exact captured archive SHA and pinned profile");
        work.require(mesh.packet().isPresent()&&mesh.quads().size()<=limits.faces(),ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.BLOCKSTATE,owner,"","Texture preparation requires a bounded complete CPU mesh packet");
        var ids=new TreeSet<AssetId>();for(var quad:mesh.quads())for(var id:requiredSprites(quad.material())) { work.check();ids.add(id);work.require(ids.size()<=limits.sprites(),ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,id,"","Unique referenced sprite/dependency budget reached"); }
        for(var id:ids)work.load(id);
        var faces=new ArrayList<TexturePreparation.Face>(mesh.quads().size());var used=new HashSet<ScanKey>();
        for(int i=0;i<mesh.quads().size();i++) { work.check();faces.add(work.face(i,mesh.quads().get(i),used)); }
        work.check();var result=TexturePreparation.success(mesh,work.sprites,faces,new TexturePreparation.Usage(work.sprites.size(),work.encodedBytes,work.rgbaBytes,work.metadataBytes,work.metadataNodes,work.frames,work.scans.size(),work.alphaPixelVisits));work.check();return result;
    }
}
