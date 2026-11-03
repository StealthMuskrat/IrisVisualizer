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
    private record ScanKey(AssetId sprite,TexturePreparation.Region region) {}
    private static final class Work {
        final ClientAssetSnapshot assets;final BlockMesh mesh;final Limits limits;final BooleanSupplier cancelled;
        final NavigableMap<AssetId,TextureSprite> sprites=new TreeMap<>();final Map<ScanKey,TexturePreparation.Alpha> scans=new HashMap<>();
        long encodedBytes,rgbaBytes,alphaPixelVisits;int metadataBytes,metadataNodes,frames;
        Work(ClientAssetSnapshot assets,BlockMesh mesh,Limits limits,BooleanSupplier cancelled) { this.assets=assets;this.mesh=mesh;this.limits=limits;this.cancelled=cancelled; }
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
            // The pinned native interpolation policy is not implemented. Conservatively retain a
            // possible blended-alpha flag, including when binary endpoint masks differ spatially.
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
        var work=new Work(assets,mesh,limits,cancelled);var owner=mesh.state().block();var receipt=assets.receipt();var original=mesh.assets();
        work.require(receipt.archiveSha256().equals(original.archiveSha256())&&receipt.version().equals(original.version())&&receipt.expectedVersion().equals(original.expectedVersion())&&receipt.version().equals(BlockModels.PROFILE)&&receipt.expectedVersion().equals(BlockModels.PROFILE),
            ModelPlan.Code.VERSION,ClientAssetSnapshot.Kind.BLOCKSTATE,owner,"","Texture preparation requires the mesh's exact captured archive SHA and pinned profile");
        work.require(mesh.packet().isPresent()&&mesh.quads().size()<=limits.faces(),ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.BLOCKSTATE,owner,"","Texture preparation requires a bounded complete CPU mesh packet");
        var ids=new TreeSet<AssetId>();for(var quad:mesh.quads()) { work.check();ids.add(quad.material().sprite());work.require(ids.size()<=limits.sprites(),ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,quad.material().sprite(),"","Unique referenced sprite budget reached"); }
        for(var id:ids)work.load(id);
        var faces=new ArrayList<TexturePreparation.Face>(mesh.quads().size());
        for(int i=0;i<mesh.quads().size();i++) {
            work.check();var quad=mesh.quads().get(i);var sprite=work.sprites.get(quad.material().sprite());var region=work.region(quad,sprite);var alpha=work.alpha(sprite,region);
            faces.add(new TexturePreparation.Face(i,quad.source(),quad.material(),region,alpha,alpha.classification(quad.material().forceTranslucent())));
        }
        work.check();var result=TexturePreparation.success(mesh,work.sprites,faces,new TexturePreparation.Usage(work.sprites.size(),work.encodedBytes,work.rgbaBytes,work.metadataBytes,work.metadataNodes,work.frames,work.scans.size(),work.alphaPixelVisits));work.check();return result;
    }
}
