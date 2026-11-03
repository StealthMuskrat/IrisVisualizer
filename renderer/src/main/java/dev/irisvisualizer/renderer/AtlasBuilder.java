package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Deterministic, bounded CPU layout shared by multiple same-archive block preparations. */
public final class AtlasBuilder {
    private AtlasBuilder() {}
    private static final long MIB=1024L*1024;
    public record Limits(int inputs,int sprites,int faces,int scheduleEntries,int pageDimension,int pages,int gutter,long sourceRgbaBytes,
                         long sourcePackedBytes,long atlasBytes,long boundMeshBytes,long updateBytes,long workingBytes,long packingChecks) {
        public static final Limits DEFAULT=new Limits(1024,4096,262144,65536,4096,16,1,128*MIB,64*MIB,128*MIB,64*MIB,32*MIB,512*MIB,16777216);
        public Limits {
            if(inputs<1||inputs>1024||sprites<1||sprites>4096||faces<1||faces>262144||scheduleEntries<1||scheduleEntries>131072||pageDimension<4||pageDimension>8192||Integer.bitCount(pageDimension)!=1||pages<1||pages>32||gutter<1||gutter>8
                ||sourceRgbaBytes<0||sourceRgbaBytes>256*MIB||sourcePackedBytes<0||sourcePackedBytes>64*MIB||atlasBytes<0||atlasBytes>128*MIB||boundMeshBytes<0||boundMeshBytes>64*MIB||updateBytes<0||updateBytes>32*MIB||workingBytes<0||workingBytes>512*MIB||packingChecks<1||packingChecks>16777216)
                throw new IllegalArgumentException("Atlas limits exceed renderer bounds");
        }
    }
    static ModelFields.Failure failure(ModelPlan.Code code,AssetId id,String pointer,String reason) { return TextureMetadata.failure(code,ClientAssetSnapshot.Kind.TEXTURE,id,pointer,reason); }
    static ModelFields.Failure blockFailure(ModelPlan.Code code,AssetId id,String reason) { return TextureMetadata.failure(code,ClientAssetSnapshot.Kind.BLOCKSTATE,id,"",reason); }
    static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("CPU atlas work cancelled"); }
    /** Explicit no-texture epoch for all-empty/placeholder scenes, without inventing an admitted mesh. */
    public static AtlasLayout empty(ClientAssetSnapshot assets,AssetId owner,BooleanSupplier cancelled) { return empty(assets,owner,Limits.DEFAULT,cancelled); }
    public static AtlasLayout empty(ClientAssetSnapshot assets,AssetId owner,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(owner);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);check(cancelled);var receipt=assets.receipt();
        try {
            if(!receipt.version().equals(BlockModels.PROFILE)||!receipt.expectedVersion().equals(BlockModels.PROFILE))throw blockFailure(ModelPlan.Code.VERSION,owner,"Empty visual atlas still requires the pinned selected-client profile");
            var usage=new AtlasLayout.Usage(0,0,0,0,0,0,0,0,0,0);check(cancelled);return AtlasLayout.success(receipt,owner,limits,List.of(),List.of(),Map.of(),usage);
        }catch(ModelFields.Failure failure) { check(cancelled);return AtlasLayout.placeholder(receipt,owner,limits,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return AtlasLayout.placeholder(receipt,owner,limits,List.of(blockFailure(ModelPlan.Code.MEMORY,owner,"Not enough memory to create an empty visual atlas").problem)); }
    }
    public static AtlasLayout plan(List<TexturePreparation> preparations,BooleanSupplier cancelled) { return plan(preparations,Limits.DEFAULT,cancelled); }
    public static AtlasLayout plan(List<TexturePreparation> preparations,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(preparations);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);check(cancelled);
        if(preparations.isEmpty())throw new IllegalArgumentException("Supply at least one block texture preparation");
        var first=Objects.requireNonNull(preparations.getFirst());
        try { return execute(preparations,first,limits,cancelled); }
        catch(ModelFields.Failure failure) { check(cancelled);return AtlasLayout.placeholder(first.assets(),first.state().block(),limits,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return AtlasLayout.placeholder(first.assets(),first.state().block(),limits,List.of(blockFailure(ModelPlan.Code.MEMORY,first.state().block(),"Not enough memory to plan the atlas; no partial layout was published").problem)); }
    }
    private static AtlasLayout execute(List<TexturePreparation> inputs,TexturePreparation first,Limits limits,BooleanSupplier cancelled) {
        if(inputs.size()>limits.inputs())throw blockFailure(ModelPlan.Code.LIMIT,first.state().block(),"Atlas input count budget reached");
        var bounded=new ArrayList<TexturePreparation>();
        for(var input:inputs) { check(cancelled);if(bounded.size()>=limits.inputs())throw blockFailure(ModelPlan.Code.LIMIT,first.state().block(),"Atlas input count budget reached");bounded.add(Objects.requireNonNull(input)); }
        var captured=List.copyOf(bounded);var receipt=first.assets();
        if(!receipt.version().equals(BlockModels.PROFILE)||!receipt.expectedVersion().equals(BlockModels.PROFILE))throw blockFailure(ModelPlan.Code.VERSION,first.state().block(),"Atlas requires the pinned client profile");
        var sprites=new TreeMap<AssetId,TextureSprite>();var images=new IdentityHashMap<RgbaImage,Boolean>();var packets=new IdentityHashMap<MeshPacket,Boolean>();var schedules=new IdentityHashMap<TextureSprite,Boolean>();
        long rgba=0,packed=0;int faces=0,scheduleEntries=0;
        for(var input:captured) {
            check(cancelled);if(input.requiresPlaceholder())throw new ModelFields.Failure(input.problems().getFirst());
            if(!AtlasLayout.sameAssets(receipt,input.assets()))throw blockFailure(ModelPlan.Code.VERSION,input.state().block(),"Cannot combine different captured asset archives in one atlas");
            var packet=input.mesh().packet().orElseThrow(()->blockFailure(ModelPlan.Code.ATLAS,input.state().block(),"Texture preparation has no complete CPU mesh packet"));
            if(input.faces().size()!=packet.faces().size()||input.faces().size()>limits.faces()-faces)throw blockFailure(ModelPlan.Code.LIMIT,input.state().block(),"Atlas face count or complete-packet invariant failed");faces+=input.faces().size();
            if(packets.put(packet,Boolean.TRUE)==null)packed+=packet.packedBytes();
            if(packed>limits.sourcePackedBytes())throw blockFailure(ModelPlan.Code.LIMIT,input.state().block(),"Retained source mesh payload budget reached");
            for(var entry:input.sprites().entrySet()) {
                check(cancelled);var sprite=entry.getValue();
                if(!entry.getKey().equals(sprite.id()))throw failure(ModelPlan.Code.ATLAS,entry.getKey(),"","Sprite key does not match its source identity");
                if(images.put(sprite.image(),Boolean.TRUE)==null)rgba+=sprite.image().bytes();
                if(rgba>limits.sourceRgbaBytes())throw failure(ModelPlan.Code.LIMIT,sprite.id(),"","Retained source image payload budget reached");
                if(schedules.put(sprite,Boolean.TRUE)==null) {
                    if(sprite.frames().size()>limits.scheduleEntries()-scheduleEntries)throw failure(ModelPlan.Code.LIMIT,sprite.id(),"","Retained animation schedule-entry budget reached");scheduleEntries+=sprite.frames().size();
                }
                var previous=sprites.putIfAbsent(sprite.id(),sprite);
                if(previous!=null&&!AtlasLayout.sameSprite(previous,sprite))throw failure(ModelPlan.Code.ATLAS,sprite.id(),"","Conflicting sprite content or animation metadata in one asset epoch");
                if(sprites.size()>limits.sprites())throw failure(ModelPlan.Code.LIMIT,sprite.id(),"","Atlas unique sprite budget reached");
            }
        }
        long bound=(long)faces*MeshPacket.PACKED_BYTES_PER_QUAD;
        if(bound>limits.boundMeshBytes()||rgba+packed+bound>limits.workingBytes())throw blockFailure(ModelPlan.Code.LIMIT,first.state().block(),"Combined source and prospective bound-mesh payload budget reached");
        var placement=AtlasPacking.pack(sprites,limits,cancelled);
        var usage=new AtlasLayout.Usage(captured.size(),faces,sprites.size(),scheduleEntries,rgba,packed,placement.bytes(),bound,placement.paddedPixels(),placement.checks());
        if(usage.knownPayloadBytes()>limits.workingBytes())throw blockFailure(ModelPlan.Code.LIMIT,first.state().block(),"Combined source, mesh and atlas payload budget reached");
        check(cancelled);return AtlasLayout.success(receipt,first.state().block(),limits,captured,placement.pages(),placement.sprites(),usage);
    }
}
