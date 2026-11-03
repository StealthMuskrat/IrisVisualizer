package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Worker-local shared texture preparation; immutable successful sprites are reused across mesh variants. */
public final class TextureBatch {
    public record Limits(int meshes,int faces,int sprites,long sourcePackedBytes,long workingBytes,TextureMaterials.Limits resources) {
        public static final Limits DEFAULT=new Limits(1024,262144,4096,64L*1024*1024,512L*1024*1024,TextureMaterials.Limits.DEFAULT);
        public Limits { Objects.requireNonNull(resources);if(meshes<1||meshes>1024||faces<0||faces>262144||sprites<0||sprites>4096||sourcePackedBytes<0||sourcePackedBytes>64L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Shared texture limits exceed renderer bounds"); }
    }
    public record Usage(int meshes,int sprites,int failedSprites,long sourcePackedBytes,long retainedRgbaBytes,long visitedRgbaBytes,long encodedBytes,int metadataBytes,int metadataNodes,int scheduleEntries,int regions,long alphaPixelVisits) {}
    private final List<TexturePreparation> preparations;private final Optional<Usage> usage;private final List<ModelPlan.Problem> problems;
    private TextureBatch(List<TexturePreparation> preparations,Optional<Usage> usage,List<ModelPlan.Problem> problems) { this.preparations=List.copyOf(preparations);this.usage=usage;this.problems=List.copyOf(problems);if(!problems.isEmpty()&&(!preparations.isEmpty()||usage.isPresent()))throw new IllegalArgumentException("Partial texture batch escaped failure"); }
    static TextureBatch success(List<TexturePreparation> preparations,Usage usage) { return new TextureBatch(preparations,Optional.of(usage),List.of()); }
    static TextureBatch failed(List<ModelPlan.Problem> problems) { return new TextureBatch(List.of(),Optional.empty(),problems); }
    public static TextureBatch prepare(ClientAssetSnapshot assets,List<BlockMesh> meshes,BooleanSupplier cancelled) { return prepare(assets,meshes,Limits.DEFAULT,cancelled); }
    public static TextureBatch prepare(ClientAssetSnapshot assets,List<BlockMesh> meshes,Limits limits,BooleanSupplier cancelled) { return TextureMaterials.prepareBatch(assets,meshes,limits,cancelled); }
    public List<TexturePreparation> preparations() { return preparations; }
    public Optional<Usage> usage() { return usage; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    static boolean fatal(ModelPlan.Code code) { return code==ModelPlan.Code.LIMIT||code==ModelPlan.Code.MEMORY||code==ModelPlan.Code.VERSION||code==ModelPlan.Code.ATLAS||code==ModelPlan.Code.STALE_ATLAS; }
}
