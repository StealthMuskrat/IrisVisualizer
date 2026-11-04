package dev.irisvisualizer.renderer;

import java.util.*;

/** Atomic CPU texture result. Successful preparation grants no renderer/native readiness. */
public final class TexturePreparation {
    /** Reviewed 26.3 terrain thresholds; base-level RGBA8 drawing and picking share this policy. */
    public enum AlphaClass {
        OPAQUE(0f), CUTOUT(0.5f), TRANSLUCENT(0.1f);
        private final float cutoff;private final int minimumAlpha;
        AlphaClass(float cutoff) { this.cutoff=cutoff;minimumAlpha=(int)Math.ceil(cutoff*255.0); }
        public float alphaCutoff() { return cutoff; }
        public boolean acceptsAlpha(int alpha) { if(alpha<0||alpha>255)throw new IllegalArgumentException("Expected alpha8");return alpha>=minimumAlpha; }
    }
    /** Frame-local pixel bounds, exclusive at the upper edges. */
    public record Region(int x0,int y0,int x1,int y1) {
        public Region { if(x0<0||y0<0||x1<=x0||y1<=y0)throw new IllegalArgumentException("Invalid alpha scan region"); }
    }
    public record Alpha(boolean transparent,boolean partial,boolean opaque,boolean interpolationMayBlendAlpha) {
        public Alpha { if(interpolationMayBlendAlpha&&(!transparent||!opaque))throw new IllegalArgumentException("Invalid interpolated alpha summary"); }
        /** Empty native model rectangles contain no samples and select the opaque pass. */
        public boolean empty() { return !transparent&&!partial&&!opaque; }
        public Alpha or(Alpha other) { Objects.requireNonNull(other);return new Alpha(transparent||other.transparent,partial||other.partial,opaque||other.opaque,interpolationMayBlendAlpha||other.interpolationMayBlendAlpha); }
        public AlphaClass classification(boolean forceTranslucent) { return forceTranslucent||partial||interpolationMayBlendAlpha?AlphaClass.TRANSLUCENT:transparent?AlphaClass.CUTOUT:AlphaClass.OPAQUE; }
    }
    /** Original face order; mapped diagnostics and optional explicit material-scope classification. */
    public record Face(int index,ModelPlan.Source source,ModelPlan.Material material,Region region,Alpha alpha,AlphaClass classification,Optional<Alpha> materialAlpha) {
        public Face(int index,ModelPlan.Source source,ModelPlan.Material material,Region region,Alpha alpha,AlphaClass classification) { this(index,source,material,region,alpha,classification,Optional.empty()); }
        public Face {
            if(index<0)throw new IllegalArgumentException("Invalid texture face index");Objects.requireNonNull(source);Objects.requireNonNull(material);Objects.requireNonNull(region);Objects.requireNonNull(alpha);Objects.requireNonNull(classification);Objects.requireNonNull(materialAlpha);
            if(alpha.empty())throw new IllegalArgumentException("Mapped alpha region must contain samples");
            boolean explicit=!material.alphaSprites().isEmpty()||material.alphaUv().isPresent();
            if(explicit!=materialAlpha.isPresent())throw new IllegalArgumentException("Material alpha scope differs from its declaration");
            var resolved=materialAlpha.orElse(alpha);
            if(!material.alphaSprites().isEmpty()&&(alpha.transparent()&&!resolved.transparent()||alpha.partial()&&!resolved.partial()||alpha.opaque()&&!resolved.opaque()))throw new IllegalArgumentException("Material alpha group excludes mapped source pixels");
            if(classification!=resolved.classification(material.forceTranslucent()))throw new IllegalArgumentException("Inconsistent face alpha classification");
        }
    }
    public record Usage(int sprites,long encodedBytes,long rgbaBytes,int metadataBytes,int metadataNodes,int frames,int regions,long alphaPixelVisits) {
        public Usage { if(sprites<0||encodedBytes<0||rgbaBytes<0||metadataBytes<0||metadataNodes<0||frames<0||regions<0||alphaPixelVisits<0||metadataBytes>encodedBytes)throw new IllegalArgumentException("Invalid texture resource usage"); }
    }
    private final BlockMesh mesh;private final NavigableMap<AssetId,TextureSprite> sprites;private final List<Face> faces;
    private final Optional<Usage> usage;private final List<ModelPlan.Problem> problems;
    private TexturePreparation(BlockMesh mesh,Map<AssetId,TextureSprite> sprites,List<Face> faces,Optional<Usage> usage,List<ModelPlan.Problem> problems) {
        this.mesh=Objects.requireNonNull(mesh);this.sprites=Collections.unmodifiableNavigableMap(new TreeMap<>(sprites));this.faces=List.copyOf(faces);this.usage=usage;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(!sprites.isEmpty()||!faces.isEmpty()||usage.isPresent()))throw new IllegalArgumentException("Partial textures must not escape failed preparation");
    }
    static TexturePreparation success(BlockMesh mesh,Map<AssetId,TextureSprite> sprites,List<Face> faces,Usage usage) { return new TexturePreparation(mesh,sprites,faces,Optional.of(usage),List.of()); }
    static TexturePreparation placeholder(BlockMesh mesh,List<ModelPlan.Problem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new TexturePreparation(mesh,Map.of(),List.of(),Optional.empty(),problems); }
    public BlockMesh mesh() { return mesh; }
    public ClientAssetSnapshot.Receipt assets() { return mesh.assets(); }
    public RenderBlockState state() { return mesh.state(); }
    public NavigableMap<AssetId,TextureSprite> sprites() { return sprites; }
    public List<Face> faces() { return faces; }
    public Optional<Usage> usage() { return usage; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
