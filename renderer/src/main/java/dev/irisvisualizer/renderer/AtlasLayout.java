package dev.irisvisualizer.renderer;

import java.util.*;

/** Immutable CPU atlas epoch: placements and source sprites, not a GPU texture or readiness grant. */
public final class AtlasLayout {
    public record Page(int index,int width,int height) {
        public Page { if(index<0||width<1||height<1||width>8192||height>8192||Integer.bitCount(width)!=1||Integer.bitCount(height)!=1)throw new IllegalArgumentException("Invalid atlas page"); }
        public long bytes() { return (long)width*height*4; }
    }
    /** x/y locate the content; the edge-extruded allocation extends gutter pixels on all sides. */
    public record Sprite(TextureSprite source,int page,int x,int y,int gutter) {
        public Sprite { Objects.requireNonNull(source);if(page<0||gutter<1||gutter>8||x<gutter||y<gutter)throw new IllegalArgumentException("Invalid atlas placement"); }
        public int width() { return source.frameWidth(); }
        public int height() { return source.frameHeight(); }
        public int paddedWidth() { return width()+2*gutter; }
        public int paddedHeight() { return height()+2*gutter; }
        public long paddedBytes() { return (long)paddedWidth()*paddedHeight()*4; }
    }
    /** Known primitive payloads only; object/metadata, captured ZIP and native/world memory are excluded. */
    public record Usage(int inputs,int faces,int sprites,int scheduleEntries,long sourceRgbaBytes,long sourcePackedBytes,
                        long atlasBytes,long boundMeshBytes,long paddedPixels,long packingChecks) {
        public Usage { if(inputs<0||faces<0||sprites<0||scheduleEntries<0||sourceRgbaBytes<0||sourcePackedBytes<0||atlasBytes<0||boundMeshBytes<0||paddedPixels<0||packingChecks<0||inputs==0&&(faces!=0||sprites!=0||scheduleEntries!=0||sourceRgbaBytes!=0||sourcePackedBytes!=0||atlasBytes!=0||boundMeshBytes!=0||paddedPixels!=0||packingChecks!=0))throw new IllegalArgumentException("Invalid atlas usage"); }
        public long knownPayloadBytes() { return sourceRgbaBytes+sourcePackedBytes+atlasBytes+boundMeshBytes; }
    }
    private final ClientAssetSnapshot.Receipt assets;private final AssetId owner;private final AtlasBuilder.Limits limits;
    private final List<TexturePreparation> inputs;private final List<Page> pages;private final NavigableMap<AssetId,Sprite> sprites;private final Optional<Usage> usage;private final List<ModelPlan.Problem> problems;
    private AtlasLayout(ClientAssetSnapshot.Receipt assets,AssetId owner,AtlasBuilder.Limits limits,List<TexturePreparation> inputs,List<Page> pages,Map<AssetId,Sprite> sprites,Optional<Usage> usage,List<ModelPlan.Problem> problems) {
        this.assets=Objects.requireNonNull(assets);this.owner=Objects.requireNonNull(owner);this.limits=Objects.requireNonNull(limits);this.pages=List.copyOf(pages);
        this.inputs=List.copyOf(inputs);this.sprites=Collections.unmodifiableNavigableMap(new TreeMap<>(sprites));this.usage=Objects.requireNonNull(usage);this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(!inputs.isEmpty()||!pages.isEmpty()||!sprites.isEmpty()||usage.isPresent()))throw new IllegalArgumentException("Partial atlas layout escaped a failed plan");
    }
    static AtlasLayout success(ClientAssetSnapshot.Receipt assets,AssetId owner,AtlasBuilder.Limits limits,List<TexturePreparation> inputs,List<Page> pages,Map<AssetId,Sprite> sprites,Usage usage) { return new AtlasLayout(assets,owner,limits,inputs,pages,sprites,Optional.of(usage),List.of()); }
    static AtlasLayout placeholder(ClientAssetSnapshot.Receipt assets,AssetId owner,AtlasBuilder.Limits limits,List<ModelPlan.Problem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new AtlasLayout(assets,owner,limits,List.of(),List.of(),Map.of(),Optional.empty(),problems); }
    public ClientAssetSnapshot.Receipt assets() { return assets; }
    public AtlasBuilder.Limits limits() { return limits; }
    public List<TexturePreparation> inputs() { return inputs; }
    public List<Page> pages() { return pages; }
    public NavigableMap<AssetId,Sprite> sprites() { return sprites; }
    public Optional<Usage> usage() { return usage; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    AssetId owner() { return owner; }
    boolean admits(TexturePreparation input) { return inputs.contains(input); }
    static boolean sameAssets(ClientAssetSnapshot.Receipt a,ClientAssetSnapshot.Receipt b) { return a.archiveSha256().equals(b.archiveSha256())&&a.version().equals(b.version())&&a.expectedVersion().equals(b.expectedVersion()); }
    static boolean sameSprite(TextureSprite a,TextureSprite b) {
        return a.id().equals(b.id())&&a.pngSha256().equals(b.pngSha256())&&a.metadataSha256().equals(b.metadataSha256())&&a.image().width()==b.image().width()&&a.image().height()==b.image().height()
            &&a.frameWidth()==b.frameWidth()&&a.frameHeight()==b.frameHeight()&&a.frames().equals(b.frames())&&a.settings().equals(b.settings())&&a.animated()==b.animated()&&a.interpolate()==b.interpolate();
    }
}
