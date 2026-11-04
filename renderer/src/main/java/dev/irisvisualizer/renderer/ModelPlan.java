package dev.irisvisualizer.renderer;

import java.util.*;

/** Resolved JSON-model description, not a baked mesh or permission to preview a native world. */
public record ModelPlan(ClientAssetSnapshot.Receipt assets,RenderBlockState state,List<Part> parts,List<Problem> problems) {
    public ModelPlan { Objects.requireNonNull(assets);Objects.requireNonNull(state);parts=List.copyOf(parts);problems=List.copyOf(problems);if(!problems.isEmpty() && !parts.isEmpty())throw new IllegalArgumentException("A placeholder plan must not publish partial geometry"); }
    public enum Code { VERSION, MISSING_ASSET, EXTERNAL_ASSET, FORMAT, UNSUPPORTED_FIELD, BUILTIN_MODEL, LIMIT,
        PARENT_CYCLE, TEXTURE_CYCLE, MISSING_TEXTURE_VARIABLE, PROPERTY, NO_VARIANT, AMBIGUOUS_VARIANT, UNSUPPORTED_INHERITANCE,
        SELECTION, UNSUPPORTED_GEOMETRY, GEOMETRY_RANGE, UV_RANGE, TEXTURE_FORMAT, ANIMATION, ATLAS, STALE_ATLAS, MEMORY }
    public record Problem(Code code,ClientAssetSnapshot.Kind kind,AssetId asset,String pointer,String reason) {
        public Problem { Objects.requireNonNull(code);Objects.requireNonNull(kind);Objects.requireNonNull(asset);Objects.requireNonNull(pointer);Objects.requireNonNull(reason); }
    }
    public record Source(ClientAssetSnapshot.Kind kind,AssetId asset,String pointer) {
        public Source { Objects.requireNonNull(kind);Objects.requireNonNull(asset);Objects.requireNonNull(pointer); }
        public Source(AssetId model,String pointer) { this(ClientAssetSnapshot.Kind.MODEL,model,pointer); }
    }
    public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    static <T> Map<Direction,T> directions(Map<Direction,T> input) {
        var result=new EnumMap<Direction,T>(Direction.class);
        for(var entry:input.entrySet())result.put(Objects.requireNonNull(entry.getKey()),Objects.requireNonNull(entry.getValue()));
        return Collections.unmodifiableMap(result);
    }
    public record Vec3(float x,float y,float z) { public Vec3 { if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))throw new IllegalArgumentException("Nonfinite model vector"); } }
    public record Uv(float u0,float v0,float u1,float v1) { public Uv { if(!Float.isFinite(u0)||!Float.isFinite(v0)||!Float.isFinite(u1)||!Float.isFinite(v1))throw new IllegalArgumentException("Nonfinite model UV"); } }
    /** Model-unit origin; X, then Y, then Z rotation, with optional pre-rotation per-axis rescale. */
    public record Rotation(Vec3 origin,float x,float y,float z,boolean rescale) {
        public Rotation { Objects.requireNonNull(origin);if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))throw new IllegalArgumentException("Nonfinite element rotation"); }
    }
    /** Explicit alpha scope: mapped pixels by default, a whole-source group or pre-transform model UV. */
    public record Material(AssetId sprite,boolean forceTranslucent,int colorRgb,List<AssetId> alphaSprites,Optional<Uv> alphaUv) {
        public Material(AssetId sprite,boolean forceTranslucent) { this(sprite,forceTranslucent,0xffffff,List.of()); }
        public Material(AssetId sprite,boolean forceTranslucent,int colorRgb) { this(sprite,forceTranslucent,colorRgb,List.of()); }
        public Material(AssetId sprite,boolean forceTranslucent,int colorRgb,List<AssetId> alphaSprites) { this(sprite,forceTranslucent,colorRgb,alphaSprites,Optional.empty()); }
        public Material {
            Objects.requireNonNull(sprite);Objects.requireNonNull(alphaSprites);Objects.requireNonNull(alphaUv);if((colorRgb&0xff000000)!=0)throw new IllegalArgumentException("Material color must be RGB24");
            if(alphaSprites.size()>16)throw new IllegalArgumentException("Material alpha dependencies exceed their bound");alphaSprites=List.copyOf(alphaSprites);
            if(!alphaSprites.isEmpty()&&(!alphaSprites.contains(sprite)||new HashSet<>(alphaSprites).size()!=alphaSprites.size()))throw new IllegalArgumentException("Material alpha group must contain its sprite and no duplicates");
            if(alphaUv.isPresent()) {
                var uv=alphaUv.orElseThrow();
                if(!alphaSprites.isEmpty()||Math.min(uv.u0(),uv.u1())<0||Math.min(uv.v0(),uv.v1())<0||Math.max(uv.u0(),uv.u1())>16||Math.max(uv.v0(),uv.v1())>16)throw new IllegalArgumentException("Model alpha UV must be within its own source sprite and exclude groups");
            }
        }
        Material withAlphaUv(Uv uv) { return new Material(sprite,forceTranslucent,colorRgb,alphaSprites,Optional.of(uv)); }
        public int multiplyRgb(int tint) {
            if((tint&0xff000000)!=0)throw new IllegalArgumentException("Tint must be RGB24");if(colorRgb==0xffffff)return tint;if(tint==0xffffff)return colorRgb;
            int r=(((colorRgb>>>16)*(tint>>>16)+127)/255),g=((((colorRgb>>>8)&255)*((tint>>>8)&255)+127)/255),b=(((colorRgb&255)*(tint&255)+127)/255);
            return r<<16|g<<8|b;
        }
    }
    public record Face(Optional<Uv> uv,Material material,Optional<Direction> cullface,int rotation,int tintIndex) {
        public Face { Objects.requireNonNull(uv);Objects.requireNonNull(material);Objects.requireNonNull(cullface);if(rotation<0 || rotation>270 || rotation%90!=0 || tintIndex< -1)throw new IllegalArgumentException("Invalid face metadata"); }
    }
    public record Element(Source source,Vec3 from,Vec3 to,Optional<Rotation> rotation,Optional<Direction> shadeDirectionOverride,
                          int lightEmission,Map<Direction,Face> faces) {
        public Element { Objects.requireNonNull(source);Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(rotation);Objects.requireNonNull(shadeDirectionOverride);if(lightEmission<0||lightEmission>15)throw new IllegalArgumentException("Invalid model light emission");faces=directions(faces); }
    }
    public record Model(AssetId id,List<AssetId> parentChain,boolean ambientOcclusion,List<Element> elements,Optional<Material> particle) {
        public Model { Objects.requireNonNull(id);parentChain=List.copyOf(parentChain);elements=List.copyOf(elements);Objects.requireNonNull(particle); }
    }
    public record Choice(Model model,int x,int y,int z,boolean uvlock,int weight) {
        public Choice { Objects.requireNonNull(model);if(weight<1 || !quarter(x)||!quarter(y)||!quarter(z))throw new IllegalArgumentException("Invalid model dispatch transform/weight"); }
    }
    public record Part(Source dispatch,int sourceIndex,List<Choice> choices,int totalWeight) {
        public Part {
            Objects.requireNonNull(dispatch);choices=List.copyOf(choices);if(dispatch.kind()!=ClientAssetSnapshot.Kind.BLOCKSTATE || sourceIndex<0 || choices.isEmpty() || totalWeight<1)throw new IllegalArgumentException("Invalid/empty model part");
            long sum=0;for(var choice:choices)sum+=choice.weight();if(sum!=totalWeight)throw new IllegalArgumentException("Inconsistent model weights");
        }
        /** The caller supplies a ticket from the verified client choice policy; no replacement PRNG is used. */
        public Choice choose(int ticket) {
            if(ticket<0 || ticket>=totalWeight)throw new IllegalArgumentException("Model ticket is outside its weight range");
            for(var choice:choices){if(ticket<choice.weight())return choice;ticket-=choice.weight();}throw new AssertionError("Model weight accounting");
        }
    }
    static boolean quarter(int value) { return value==0 || value==90 || value==180 || value==270; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
