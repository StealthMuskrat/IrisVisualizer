package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Version-bound vanilla colormap pixels. Does not install native global resources. */
public final class ClientColormaps {
    public enum Kind {
        GRASS("grass"), FOLIAGE("foliage"), DRY_FOLIAGE("dry_foliage");
        private final AssetId id;Kind(String name) { id=AssetId.minecraft("colormap/"+name); }
        public AssetId id() { return id; }
    }
    public static final int DIMENSION=256,PIXELS=DIMENSION*DIMENSION;
    public static final long PAYLOAD_BYTES=(long)Kind.values().length*PIXELS*Integer.BYTES;
    public record Limits(long encodedBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(4L*1024*1024,512L*1024*1024);
        public Limits { if(encodedBytes<1||encodedBytes>4L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Colormap limits exceed renderer bounds"); }
    }
    public record Source(Kind kind,AssetId id,String pngSha256,int encodedBytes) {
        public Source { Objects.requireNonNull(kind);Objects.requireNonNull(id);Objects.requireNonNull(pngSha256);if(!id.equals(kind.id())||!pngSha256.matches("[0-9a-f]{64}")||encodedBytes<1)throw new IllegalArgumentException("Invalid colormap source receipt"); }
    }
    private final ClientAssetSnapshot.Receipt assets;private final int[][] pixels;private final List<Source> sources;private final List<ModelPlan.Problem> problems;
    private ClientColormaps(ClientAssetSnapshot.Receipt assets,int[][] owned,List<Source> sources,List<ModelPlan.Problem> problems) {
        this.assets=assets;pixels=owned;this.sources=List.copyOf(sources);this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(owned.length!=0||!sources.isEmpty()))throw new IllegalArgumentException("Partial colormaps escaped failure");
    }
    public ClientAssetSnapshot.Receipt assets() { return assets; }
    public List<Source> sources() { return sources; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public long bytes() { return requiresPlaceholder()?0:PAYLOAD_BYTES; }
    /** Detached native ARGB input; callers cannot mutate the retained colormaps. */
    public int[] copyArgb(Kind kind) { Objects.requireNonNull(kind);if(requiresPlaceholder())throw new IllegalStateException("Colormap preparation failed");return pixels[kind.ordinal()].clone(); }
    public boolean matchesAssets(ClientAssetSnapshot.Receipt expected) { return AtlasLayout.sameAssets(assets,Objects.requireNonNull(expected)); }
    public void requireAssets(ClientAssetSnapshot.Receipt expected) { if(!matchesAssets(expected))throw new IllegalArgumentException("Colormaps belong to another client archive"); }
    /** Catalog, all final tables, one decoded image and the largest bounded encoded copy. */
    public static long workingBytes(ClientAssetSnapshot assets,Limits limits) { Objects.requireNonNull(assets);Objects.requireNonNull(limits);return assets.receipt().capturedAssetBytes()+PAYLOAD_BYTES+(long)PIXELS*4+limits.encodedBytes()+65536; }
    public static ClientColormaps read(ClientAssetSnapshot assets,BooleanSupplier cancelled) { return read(assets,Limits.DEFAULT,cancelled); }
    public static ClientColormaps read(ClientAssetSnapshot assets,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);AtlasBuilder.check(cancelled);
        try { return decode(assets,limits,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return failed(assets,failure.problem); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return failed(assets,problem(ModelPlan.Code.MEMORY,Kind.GRASS,"Not enough memory to decode vanilla colormaps; no partial table was published")); }
    }
    public static RenderInput<ClientColormaps> readOwned(ClientAssetSnapshot assets,Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);AtlasBuilder.check(cancelled);
        long peak=workingBytes(assets,limits);
        if(peak>limits.workingBytes())return RenderInput.own(failed(assets,problem(ModelPlan.Code.LIMIT,Kind.GRASS,"Colormap source/decode peak exceeds its working allowance")),RenderMemory.Hold.none());
        var hold=memory==null?RenderMemory.Hold.none():memory.hold("Vanilla colormaps",peak);
        try(hold) { var result=read(assets,limits,cancelled);hold.shrinkTo(result.bytes());return RenderInput.own(result,hold); }
    }
    private static ModelPlan.Problem problem(ModelPlan.Code code,Kind kind,String reason) { return new ModelPlan.Problem(code,ClientAssetSnapshot.Kind.TEXTURE,kind.id(),"",reason); }
    private static ClientColormaps failed(ClientAssetSnapshot assets,ModelPlan.Problem problem) { return new ClientColormaps(assets.receipt(),new int[0][],List.of(),List.of(problem)); }
    private static ClientColormaps decode(ClientAssetSnapshot assets,Limits limits,BooleanSupplier cancelled) {
        if(!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw new ModelFields.Failure(problem(ModelPlan.Code.VERSION,Kind.GRASS,"Vanilla colormaps require the pinned selected-client profile"));
        if(workingBytes(assets,limits)>limits.workingBytes())throw new ModelFields.Failure(problem(ModelPlan.Code.LIMIT,Kind.GRASS,"Colormap source/decode peak exceeds its working allowance"));
        var decoder=new TextureMaterials.Limits(3,1,DIMENSION,PIXELS,PIXELS*4L,limits.encodedBytes(),1,1,1,1,0);
        int[][] output=new int[Kind.values().length][];var sources=new ArrayList<Source>();long encoded=0;
        for(var kind:Kind.values()) {
            AtlasBuilder.check(cancelled);var source=assets.find(ClientAssetSnapshot.Kind.TEXTURE,kind.id()).orElseThrow(()->new ModelFields.Failure(problem(ModelPlan.Code.MISSING_ASSET,kind,"Selected archive lacks a required vanilla colormap")));
            if(source.bytes()>limits.encodedBytes()-encoded)throw new ModelFields.Failure(problem(ModelPlan.Code.LIMIT,kind,"Aggregate encoded colormap budget reached"));encoded+=source.bytes();
            output[kind.ordinal()]=decodeTable(kind,source,decoder,cancelled);sources.add(new Source(kind,kind.id(),source.sha256(),source.bytes()));
        }
        AtlasBuilder.check(cancelled);return new ClientColormaps(assets.receipt(),output,sources,List.of());
    }
    /** The decoded image and encoded copy leave this frame before the next map is allocated. */
    private static int[] decodeTable(Kind kind,ClientAssetSnapshot.Asset source,TextureMaterials.Limits limits,BooleanSupplier cancelled) {
        var image=PngDecoder.decode(kind.id(),source.copyBytes(),limits,PIXELS*4L,cancelled);
        if(image.width()!=DIMENSION||image.height()!=DIMENSION)throw new ModelFields.Failure(problem(ModelPlan.Code.TEXTURE_FORMAT,kind,"Vanilla colormaps must be exactly 256 by 256 pixels"));
        int[] argb=new int[PIXELS];
        for(int y=0;y<DIMENSION;y++) { AtlasBuilder.check(cancelled);for(int x=0;x<DIMENSION;x++) { int rgba=image.rgba(x,y);argb[y*DIMENSION+x]=(rgba&255)<<24|rgba>>>8; } }
        return argb;
    }
}
