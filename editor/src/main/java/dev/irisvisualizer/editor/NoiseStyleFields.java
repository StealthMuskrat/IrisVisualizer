package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Bounded controls on source-audited style objects; no factories or noise algorithms run. */
public final class NoiseStyleFields {
    public static final List<String> EDITABLE = List.of("style", "zoom", "exponent", "cellularFrequency", "cellularZoom");
    public static final List<String> OBJECT_FIELDS = List.of("style");
    public static final List<String> DIMENSION_FIELDS = List.of("regionStyle", "continentalStyle", "landBiomeStyle", "shoreBiomeStyle", "seaBiomeStyle", "caveBiomeStyle");
    public static final List<String> BIOME_FIELDS = List.of("childStyle", "biomeStyle");
    public static final List<String> TERRAIN_FIELDS = List.of("densityStyle", "crackStyle");
    public static final List<String> CAVE_FIELDS = List.of("baseDensityStyle", "detailDensityStyle", "warpStyle", "surfaceBreakStyle");
    public record Target(String resourceBase, String objectField, NoiseFields.Target noise, int moduleIndex, int fractureDepth) {
        public Target(String resourceBase,String objectField,NoiseFields.Target noise,int moduleIndex) { this(resourceBase,objectField,noise,moduleIndex,0); }
        public Target(String resourceBase, String objectField, NoiseFields.Target noise) { this(resourceBase, objectField, noise, -1); }
        public Target {
            if (fractureDepth<0 || fractureDepth>StyleFractures.MAXIMUM_DEPTH) throw new IllegalArgumentException("Unsupported style-fracture depth");
            if (resourceBase == null || objectField == null) throw new IllegalArgumentException("Unsupported style target");
            boolean valid = noise != null ? noise.resourceBase().equals(resourceBase) && "style".equals(objectField)
                    : "dimensions".equals(resourceBase) && DIMENSION_FIELDS.contains(objectField)
                    || "biomes".equals(resourceBase) && (BIOME_FIELDS.contains(objectField)
                    || objectField.startsWith("terrain3D.") && TERRAIN_FIELDS.contains(objectField.substring("terrain3D.".length())));
            valid = valid || noise==null && CaveProfileFields.CONTEXTS.contains(resourceBase) && objectField.startsWith("caveProfile.")
                    && (CAVE_FIELDS.contains(objectField.substring("caveProfile.".length())) || objectField.equals("caveProfile.densityThreshold.style"));
            valid = valid || noise==null && "dimensions".equals(resourceBase) && objectField.equals("hydrology.rivers.geometry.banks3D.densityStyle");
            valid = valid || noise==null && "dimensions".equals(resourceBase) && TerrainOverlays.styleIndex(objectField)>=0;
            valid = valid || noise==null && "dimensions".equals(resourceBase) && DimensionCarvings.styleIndex(objectField)>=0;
            valid = valid || noise==null && "dimensions".equals(resourceBase) && objectField.equals("dimensionStack.blend.style");
            valid = valid || noise==null && "dimensions".equals(resourceBase) && (objectField.equals("hydrology.rivers.surface.channel.width.style") || objectField.equals("hydrology.rivers.surface.channel.depth.style"));
            valid = valid || noise==null && "dimensions".equals(resourceBase) && List.of("hydrology.rivers.underground.fluidLevel.style", "hydrology.rivers.underground.channelWidth.style", "hydrology.rivers.underground.depth.style", "hydrology.rivers.underground.headroom.style").contains(objectField);
            valid = valid || noise==null && "dimensions".equals(resourceBase) && RiverPaletteFields.targetForStylePath(objectField)!=null;
            valid = valid || noise==null && "dimensions".equals(resourceBase) && RiverProfiles.styleIndex(objectField)>=0;
            valid = valid || noise==null && "dimensions".equals(resourceBase) && SurfacePools.styleIndex(objectField)>=0;
            valid = valid || noise==null && "dimensions".equals(resourceBase) && (DeepFluids.styleIndex(objectField)>=0 || DeepFluids.heightStyleIndex(objectField)>=0);
            valid = valid || noise==null && "dimensions".equals(resourceBase) && List.of("rockPalette.style","fluidPalette.style").contains(objectField);
            valid = valid || noise==null && "biomes".equals(resourceBase) && BiomeLayers.styleTarget(objectField)!=null;
            valid = valid || noise==null && "biomes".equals(resourceBase) && List.of("slab.style","wall.style").contains(objectField);
            valid = valid || noise==null && "biomes".equals(resourceBase) && FloatingNoiseStyles.context(objectField)!=null;
            valid = valid || noise==null && "biomes".equals(resourceBase) && FloatingBottomPalettes.context(objectField)!=null;
            valid = valid || noise==null && FloatingPlacementSettings.styleContext(resourceBase,objectField)!=null;
            valid = valid || noise==null && FloatingPlacementConstraints.warpContext(resourceBase,objectField)!=null;
            valid = valid || noise==null && FloatingObjectPalettes.styleContext(resourceBase,objectField)!=null;
            valid = valid || noise==null && OreGenerators.styleContext(resourceBase,objectField)!=null;
            valid = valid || noise==null && BiomeDecorators.styleContext(resourceBase,objectField)!=null;
            valid = moduleIndex == -1 ? valid : moduleIndex >= 0 && noise == null && CaveProfileFields.CONTEXTS.contains(resourceBase) && objectField.equals("caveProfile.modules.style");
            if (!valid) throw new IllegalArgumentException("Unsupported style target");
        }
        public static Target noise(NoiseFields.Target target) { return new Target(java.util.Objects.requireNonNull(target).resourceBase(), "style", target); }
        public static Target dimension(String field) { return new Target("dimensions", field, null); }
        public static Target biome(String field) { return new Target("biomes", field, null); }
        public static Target terrain(String field) { return new Target("biomes","terrain3D."+field,null); }
        public static Target cave(String context, String field) {
            if (!CAVE_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported cave style");
            return new Target(context,"caveProfile."+field,null);
        }
        public static Target threshold(String context) { return new Target(context,"caveProfile.densityThreshold.style",null); }
        public static Target module(String context, int index) {
            if (index < 0) throw new IllegalArgumentException("Invalid cave module index");
            return new Target(context,"caveProfile.modules.style",null,index);
        }
        public static Target bank() { return new Target("dimensions","hydrology.rivers.geometry.banks3D.densityStyle",null); }
        public static Target channelRange(String field) {
            if (!RiverChannelRanges.OBJECT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported channel range style");
            return new Target("dimensions","hydrology.rivers.surface.channel."+field+".style",null);
        }
        public static Target undergroundRange(String field) {
            if (!RiverChannelRanges.UNDERGROUND_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported underground range style");
            return new Target("dimensions","hydrology.rivers.underground."+field+".style",null);
        }
        public static Target ore(OreGenerators.Context context,String field) {
            if (context==null || !List.of("chanceStyle","palette").contains(field)) throw new IllegalArgumentException("Select an ore style");
            return new Target(context.resourceBase(),"ores."+context.index()+"."+field+(field.equals("palette") ? ".style" : ""),null);
        }
        public boolean decorator() { return BiomeDecorators.styleContext(resourceBase,objectField)!=null; }
        public BiomeDecorators.StyleContext decoratorContext() { var context=BiomeDecorators.styleContext(resourceBase,objectField);if (context==null) throw new IllegalArgumentException("Not a decorator style");return context; }
        public boolean ore() { return OreGenerators.styleContext(resourceBase,objectField)!=null; }
        public OreGenerators.StyleContext oreContext() { var context=OreGenerators.styleContext(resourceBase,objectField);if (context==null) throw new IllegalArgumentException("Not an ore style");return context; }
        public static Target objectPalette(FloatingObjectPalettes.Context context) { return new Target(java.util.Objects.requireNonNull(context).resourceBase(),context.stylePath(),null); }
        public boolean objectPalette() { return FloatingObjectPalettes.styleContext(resourceBase,objectField)!=null; }
        public FloatingObjectPalettes.Context objectPaletteContext() { var context=FloatingObjectPalettes.styleContext(resourceBase,objectField);if (context==null) throw new IllegalArgumentException("Not an object material palette style");return context; }
        public static Target placementWarp(int entry,String list,int placement) { var context=new FloatingPlacementConstraints.Context(entry,list,placement);return new Target(FloatingObjectPlacements.resourceBase(context.entry()),FloatingObjectPlacements.contextPath(context.entry(),context.list(),context.placement())+".warp",null); }
        public boolean placementWarp() { return FloatingPlacementConstraints.warpContext(resourceBase,objectField)!=null; }
        public FloatingPlacementConstraints.Context warpContext() { var context=FloatingPlacementConstraints.warpContext(resourceBase,objectField);if (context==null) throw new IllegalArgumentException("Not a floating placement warp");return context; }
        public static Target placementDensity(int entry,String list,int placement) { var context=new FloatingPlacementSettings.Context(entry,list,placement);return new Target(FloatingObjectPlacements.resourceBase(context.entry()),FloatingObjectPlacements.contextPath(context.entry(),context.list(),context.placement())+".densityStyle.style",null); }
        public boolean placementDensity() { return FloatingPlacementSettings.styleContext(resourceBase,objectField)!=null; }
        public FloatingPlacementSettings.Context placementContext() { var context=FloatingPlacementSettings.styleContext(resourceBase,objectField);if (context==null) throw new IllegalArgumentException("Not a floating placement density style");return context; }
        public static Target floatingBottom(int entry,int layer) { if (entry<0 || layer<0) throw new IllegalArgumentException("Select a floating entry and layer");return new Target("biomes","floatingChildBiomes."+entry+".bottomPalette."+layer+".style",null); }
        public boolean floatingBottom() { return FloatingBottomPalettes.context(objectField)!=null; }
        public FloatingBottomPalettes.Context floatingBottomContext() { var context=FloatingBottomPalettes.context(objectField);if (context==null) throw new IllegalArgumentException("Not a floating underside style");return context; }
        public static Target floating(int index,String field) { FloatingNoiseStyles.field(field);if (index<0) throw new IllegalArgumentException("Select a floating entry");return new Target("biomes","floatingChildBiomes."+index+"."+field,null); }
        public boolean floating() { return FloatingNoiseStyles.context(objectField)!=null; }
        public FloatingNoiseStyles.Context floatingContext() { var context=FloatingNoiseStyles.context(objectField);if (context==null) throw new IllegalArgumentException("Not a floating style");return context; }
        public Target root() { return fractureDepth==0 ? this : new Target(resourceBase,objectField,noise,moduleIndex,0); }
        public Target child() { return new Target(resourceBase,objectField,noise,moduleIndex,fractureDepth+1); }
        public boolean optionalFracture() { return fractureDepth>0; }
        public boolean optionalFloating() { return fractureDepth==0 && floating() && FloatingNoiseStyles.optional(floatingContext().field()); }
        public static Target slabWall(String field) { BiomeSlabWall.field(field);return new Target("biomes",field+".style",null); }
        public boolean slabWall() { return List.of("slab.style","wall.style").contains(objectField); }
        public String slabWallField() { if (!slabWall()) throw new IllegalArgumentException("Not a slab/wall style");return objectField.substring(0,objectField.length()-".style".length()); }
        public static Target biomeLayer(String list,int index) { BiomeLayers.field(list);if (index<0) throw new IllegalArgumentException("Select a layer");return new Target("biomes",list+"."+index+".style",null); }
        public boolean biomeLayer() { return BiomeLayers.styleTarget(objectField)!=null; }
        public BiomeLayers.Layer layerTarget() { var target=BiomeLayers.styleTarget(objectField);if (target==null) throw new IllegalArgumentException("Not a biome layer style");return target; }
        public static Target dimensionPalette(String field) { if (field==null || !DimensionPalettes.OBJECT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported root palette style");return new Target("dimensions",field+".style",null); }
        public boolean dimensionPalette() { return List.of("rockPalette.style","fluidPalette.style").contains(objectField); }
        public String dimensionPaletteField() { if (!dimensionPalette()) throw new IllegalArgumentException("Not a root palette style");return objectField.substring(0,objectField.length()-".style".length()); }
        public static Target deepPalette(int index) { if (index<0) throw new IllegalArgumentException("Select a deep-fluid system");return new Target("dimensions","hydrology.deepFluids."+index+".fluidPalette.style",null); }
        public static Target deepHeight(int index) { if (index<0) throw new IllegalArgumentException("Select a deep-fluid system");return new Target("dimensions","hydrology.deepFluids."+index+".height.style",null); }
        public boolean deepPalette() { return DeepFluids.styleIndex(objectField)>=0; }
        public boolean deepHeight() { return DeepFluids.heightStyleIndex(objectField)>=0; }
        public int deepIndex() { int index=deepPalette() ? DeepFluids.styleIndex(objectField) : DeepFluids.heightStyleIndex(objectField);if (index<0) throw new IllegalArgumentException("Not a deep-fluid style");return index; }
        public static Target poolPalette(int index) { if (index<0) throw new IllegalArgumentException("Select a pool");return new Target("dimensions","hydrology.surfacePools."+index+".fluidPalette.style",null); }
        public boolean poolPalette() { return SurfacePools.styleIndex(objectField)>=0; }
        public int poolIndex() { int index=SurfacePools.styleIndex(objectField);if (index<0) throw new IllegalArgumentException("Not a pool palette");return index; }
        public static Target profilePalette(int index) { if (index<0) throw new IllegalArgumentException("Select a profile");return new Target("dimensions","hydrology.rivers.profiles."+index+".fluidPalette.style",null); }
        public boolean profilePalette() { return RiverProfiles.styleIndex(objectField)>=0; }
        public int profileIndex() { int index=RiverProfiles.styleIndex(objectField);if (index<0) throw new IllegalArgumentException("Not a profile palette");return index; }
        public static Target palette(String target) { return new Target("dimensions",RiverPaletteFields.stylePath(target),null); }
        public boolean palette() { return RiverPaletteFields.targetForStylePath(objectField)!=null; }
        public String paletteTarget() { String target=RiverPaletteFields.targetForStylePath(objectField); if (target==null) throw new IllegalArgumentException("Not a river palette style"); return target; }
        public boolean undergroundRange() { return List.of("hydrology.rivers.underground.fluidLevel.style", "hydrology.rivers.underground.channelWidth.style", "hydrology.rivers.underground.depth.style", "hydrology.rivers.underground.headroom.style").contains(objectField); }
        public boolean riverRange() { return channelRange() || undergroundRange(); }
        public boolean channelRange() { return objectField.equals("hydrology.rivers.surface.channel.width.style") || objectField.equals("hydrology.rivers.surface.channel.depth.style"); }
        public String rangeField() { if (undergroundRange()) return "underground."+objectField.substring("hydrology.rivers.underground.".length(),objectField.length()-".style".length()); if (!channelRange()) throw new IllegalArgumentException("Not a channel range style"); return objectField.substring("hydrology.rivers.surface.channel.".length(),objectField.length()-".style".length()); }
        public boolean overlay() { return "dimensions".equals(resourceBase) && TerrainOverlays.styleIndex(objectField)>=0; }
        public int overlayIndex() { int index=TerrainOverlays.styleIndex(objectField);if (index<0 || !overlay()) throw new IllegalArgumentException("Not an overlay style");return index; }
        public boolean carving() { return "dimensions".equals(resourceBase) && DimensionCarvings.styleIndex(objectField)>=0; }
        public int carvingIndex() { int index=DimensionCarvings.styleIndex(objectField);if (index<0 || !carving()) throw new IllegalArgumentException("Not a dimension carving style");return index; }
        public boolean stackBlend() { return objectField.equals("dimensionStack.blend.style"); }
        public boolean banks() { return objectField.equals("hydrology.rivers.geometry.banks3D.densityStyle"); }
        public boolean strictBounds() { return fractureDepth==0 && (terrain() || banks()); }
        public boolean terrain() { return objectField.startsWith("terrain3D."); }
        public boolean cave() { return objectField.startsWith("caveProfile."); }
        public boolean threshold() { return objectField.equals("caveProfile.densityThreshold.style"); }
        public boolean module() { return moduleIndex >= 0; }
        public String member() { return fractureDepth>0 ? "fracture" : overlay() ? "generator" : carving() ? "childStyle" : stackBlend() ? "style" : decorator() ? decoratorContext().field() : ore() ? oreContext().member() : objectPalette() ? "style" : placementWarp() ? "warp" : placementDensity() ? "style" : floating() ? floatingContext().field() : floatingBottom() || slabWall() || biomeLayer() || dimensionPalette() || deepHeight() || deepPalette() ? "style" : poolPalette() ? "style" : profilePalette() ? "style" : palette() ? "style" : riverRange() ? "style" : banks() ? "densityStyle" : module() || threshold() ? "style" : terrain() ? objectField.substring("terrain3D.".length()) : cave() ? objectField.substring("caveProfile.".length()) : objectField; }
        public String draftPrefix() { if (fractureDepth>0) return root().draftPrefix()+"fracture.".repeat(fractureDepth);return overlay() ? TerrainOverlays.prefix(overlayIndex())+"generator." : carving() ? DimensionCarvings.prefix(carvingIndex())+"childStyle." : stackBlend() ? DimensionStacks.blendPrefix()+"style." : decorator() ? decoratorContext().owner().prefix()+decoratorContext().field()+"." : ore() ? oreContext().prefix() : objectPalette() ? objectPaletteContext().prefix()+"noise.style." : placementWarp() ? warpContext().prefix("warp") : placementDensity() ? placementContext().prefix("densityStyle")+"style." : floatingBottom() ? FloatingBottomPalettes.noisePrefix(floatingBottomContext().entry(),floatingBottomContext().layer())+"style." : floating() ? FloatingNoiseStyles.prefix(floatingContext().index(),floatingContext().field()) : slabWall() ? BiomeSlabWall.noisePrefix(slabWallField())+"style." : biomeLayer() ? BiomeLayers.noisePrefix(layerTarget().field(),layerTarget().index())+"style." : dimensionPalette() ? DimensionPalettes.noisePrefix(dimensionPaletteField())+"style." : deepHeight() ? DeepFluids.systemPrefix(deepIndex())+"height.style." : deepPalette() ? DeepFluids.noisePrefix(deepIndex())+"style." : poolPalette() ? SurfacePools.noisePrefix(poolIndex())+"style." : profilePalette() ? RiverProfiles.noisePrefix(profileIndex())+"style." : palette() ? RiverPaletteFields.draftPrefix(paletteTarget())+"style." : riverRange() ? RiverChannelRanges.draftPrefix(rangeField())+"style." : banks() ? RiverBankFields.draftPrefix()+"densityStyle." : module() ? CaveModules.draftPrefix(resourceBase,moduleIndex)+"style." : threshold() ? CaveThresholdFields.draftPrefix(resourceBase)+"style." : cave() ? "caveProfile#"+resourceBase+"."+member()+"." : terrain() ? "terrain3D#"+member()+"." : noise == null ? "rootStyle#"+resourceBase+"."+objectField+"." : noise.draftPrefix()+"style."; }
    }
    private static final Properties DEFINITIONS = new Properties();
    private static final List<String> CHOICES;
    static {
        try (var input = NoiseStyleFields.class.getResourceAsStream("/style-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned style definitions");
            DEFINITIONS.load(input);
            CHOICES = List.of(DEFINITIONS.getProperty("choices", "").split(","));
            if (CHOICES.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*"))
                    || CHOICES.stream().distinct().count() != CHOICES.size()
                    || !CHOICES.contains(noiseDefault()) || !CHOICES.contains(objectDefault()))
                throw new IOException("Invalid pinned noise style choices");
            for (String group : List.of("dimensions", "biomes"))
                for (String field : group.equals("dimensions") ? DIMENSION_FIELDS : BIOME_FIELDS)
                    if (!CHOICES.contains(DEFINITIONS.getProperty(group+"."+field+".default", "")))
                        throw new IOException("Missing pinned contextual style default");
            if (!"finiteNonnegative".equals(DEFINITIONS.getProperty("cellularFrequency.runtimeRule"))
                    || !"positiveFiniteReciprocalWhenActive".equals(DEFINITIONS.getProperty("cellularZoom.runtimeRule")))
                throw new IOException("Missing pinned cellular validation rules");
            if (!FloatingNoiseStyles.classStyle().equals(objectDefault())) throw new IOException("Floating optional style class default differs");
            for (String field : FloatingNoiseStyles.EDITABLE) if (!CHOICES.contains(FloatingNoiseStyles.creationStyle(field))) throw new IOException("Invalid floating style default");
            if (!CHOICES.contains(FloatingPlacementConstraints.warpDefault()) || !objectDefault().equals(FloatingPlacementConstraints.warpDefault())) throw new IOException("Invalid placement warp default");
            if (!CHOICES.contains(FloatingPlacementSettings.styleDefault())) throw new IOException("Invalid placement density style default");
            if (!CHOICES.contains(BiomeLayers.styleDefault())) throw new IOException("Invalid biome layer style default");
            if (!CHOICES.contains(DeepFluids.heightClassStyle())) throw new IOException("Invalid deep-fluid height style default");
            if (!CHOICES.contains(RiverPaletteFields.styleDefault()) || !objectDefault().equals(RiverPaletteFields.styleObjectDefault())) throw new IOException("Missing pinned palette style default");
            if (!CHOICES.contains(RiverBankFields.styleDefault())) throw new IOException("Missing pinned bank style default");
            for (String field : TERRAIN_FIELDS)
                if (!CHOICES.contains(Terrain3DFields.styleDefault(field))) throw new IOException("Missing pinned terrain style default");
            for (String field : CAVE_FIELDS) {
                String value=CaveProfileFields.styleDefault(field),override=CaveProfileFields.styleZoomOverride(field);
                if (value==null || !CHOICES.contains(value)) throw new IOException("Missing pinned cave style default");
                if (override!=null) {
                    double zoom=Double.parseDouble(override);
                    if (!Double.isFinite(zoom) || zoom<Double.parseDouble(DEFINITIONS.getProperty("zoom.minimum"))) throw new IOException("Invalid pinned cave zoom override");
                }
            }
            if (!CHOICES.contains(CaveThresholdFields.defaultStyle(false)) || !CHOICES.contains(CaveThresholdFields.defaultStyle(true)))
                throw new IOException("Invalid pinned cave threshold styles");
            if (!CHOICES.contains(RiverChannelRanges.classStyle()) || RiverChannelRanges.rangeKeys().stream().anyMatch(field -> !CHOICES.contains(RiverChannelRanges.contextStyle(field)))) throw new IOException("Invalid pinned channel range styles");
            if (!CHOICES.contains(CaveModules.styleDefault())) throw new IOException("Invalid pinned cave module style");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static List<String> choices() { return CHOICES; }
    static boolean readOnly(LosslessJson style,String field) { return field.equals("fracture") ? !StyleFractures.supported(style) : !EDITABLE.contains(field) && !NoiseStyleSources.supported(style,field); }
    public static String noiseDefault() { return DEFINITIONS.getProperty("noiseDefault"); }
    public static String objectDefault() { return DEFINITIONS.getProperty("objectDefault"); }
    public static String contextDefault(Target target) { return target.optionalFracture() ? objectDefault() : target.overlay() ? TerrainOverlays.styleDefault() : target.carving() ? DimensionCarvings.styleDefault() : target.stackBlend() ? DimensionStacks.styleDefault() : target.decorator() ? BiomeDecorators.styleDefault() : target.ore() ? OreGenerators.styleDefault() : target.objectPalette() ? RiverPaletteFields.styleDefault() : target.placementWarp() ? FloatingPlacementConstraints.warpDefault() : target.placementDensity() ? FloatingPlacementSettings.styleDefault() : target.floating() ? FloatingNoiseStyles.creationStyle(target.floatingContext().field()) : target.floatingBottom() || target.slabWall() || target.biomeLayer() ? BiomeLayers.styleDefault() : target.deepHeight() ? DeepFluids.heightClassStyle() : target.dimensionPalette() || target.deepPalette() || target.poolPalette() || target.profilePalette() || target.palette() ? RiverPaletteFields.styleDefault() : target.riverRange() ? RiverChannelRanges.classStyle() : target.banks() ? RiverBankFields.styleDefault() : target.module() ? CaveModules.styleDefault() : target.threshold() ? CaveThresholdFields.defaultStyle(false) : target.cave() ? CaveProfileFields.styleDefault(target.member()) : target.terrain() ? Terrain3DFields.styleDefault(target.member()) : target.noise() == null ? DEFINITIONS.getProperty(target.resourceBase()+"."+target.objectField()+".default") : noiseDefault(); }
    public static String contextDescription(Target target) { return target.optionalFracture() ? StyleFractures.help() : target.overlay() ? TerrainOverlays.help("generator") : target.carving() ? DimensionCarvings.help("childStyle") : target.stackBlend() ? DimensionStacks.help("style") : target.decorator() ? BiomeDecorators.help(target.decoratorContext().field()) : target.ore() ? OreGenerators.help(target.oreContext().field()) : target.objectPalette() ? "Native object material palette style; sampling and registry resolution remain unavailable." : target.placementWarp() ? FloatingPlacementConstraints.help("warp") : target.placementDensity() ? "Style for sampled placement density; expressions/image maps can override the base choice." : target.floating() ? FloatingNoiseStyles.description(target.floatingContext().field()) : target.floatingBottom() || target.slabWall() || target.biomeLayer() ? BiomeLayers.styleDescription() : target.deepHeight() ? RiverChannelRanges.styleDescription() : target.dimensionPalette() || target.deepPalette() || target.poolPalette() || target.profilePalette() || target.palette() ? RiverPaletteFields.description("style") : target.riverRange() ? RiverChannelRanges.styleDescription() : target.banks() ? RiverBankFields.description("densityStyle") : target.module() ? CaveModules.help("style") : target.threshold() ? CaveThresholdFields.styleDescription() : target.cave() ? CaveProfileFields.help(target.member()) : target.terrain() ? Terrain3DFields.description(target.member()) : target.noise() == null ? DEFINITIONS.getProperty(target.resourceBase()+"."+target.objectField()+".description", "") : description("style"); }
    private static String contextZoomOverride(Target target) {
        return target.optionalFracture() ? null : target.floating() ? FloatingNoiseStyles.zoomOverride(target.floatingContext().field()) : target.cave() && !target.threshold() && !target.module() ? CaveProfileFields.styleZoomOverride(target.member()) : null;
    }
    public static String contextDefaultLabel(Target target) {
        String override=contextZoomOverride(target);
        return contextDefault(target)+(override==null ? "" : ", zoom "+override);
    }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String constraints(String field) {
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        return "Finite number" + (min == null ? "" : ", minimum " + min) + (max == null ? "" : ", maximum " + max)
                + (field.equals("cellularZoom") ? "; positive zoom required while cellular frequency > 0" : "");
    }
    public static String constraints(Target target, String field) {
        if (!target.strictBounds()) return constraints(field);
        String maximum=bound(target,field,"maximum");
        return "Finite number, minimum "+bound(target,field,"minimum")
                + (Double.parseDouble(maximum)==Double.MAX_VALUE ? "" : ", maximum "+maximum);
    }
    private static String bound(Target target, String field, String kind) {
        return target.optionalFracture() ? DEFINITIONS.getProperty(field+"."+kind) : target.banks() ? RiverBankFields.styleBound(field,kind) : target.terrain() ? Terrain3DFields.styleBound(field,kind) : DEFINITIONS.getProperty(field+"."+kind);
    }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field) || field.equals("style")) throw new IllegalArgumentException("Not a style scalar");
        return new java.math.BigDecimal(DEFINITIONS.getProperty(field + ".default")).stripTrailingZeros().toPlainString();
    }
    private static void authorize(String path, Target target, String field) {
        if (!target.resourceBase().equals(ResourceTypes.base(path)) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Style field is read-only");
    }
    public static byte[] enable(String path, byte[] original, NoiseFields.Target target, String field) {
        return enable(path, original, Target.noise(target), field);
    }
    public static byte[] enable(String path, byte[] original, Target target, String field) {
        authorize(path, target, field);
        return editContainer(original, target, container -> {
            String stored=container.token(target.member());
            if (stored == null || (target.optionalFloating() || target.optionalFracture()) && stored.equals("null")) {
                if (stored!=null) container=new LosslessJson(container.enableObject(target.member()));
                byte[] seeded = container.enableStringPath(target.member()+".style", contextDefault(target));
                String override=contextZoomOverride(target);
                if (override!=null) seeded=new LosslessJson(seeded).enablePath(target.member()+".zoom",override);
                if (field.equals("style") || new LosslessJson(seeded).tokenPath(target.member()+"."+field)!=null) return seeded;
                container = new LosslessJson(seeded);
            }
            return container.objectEdit(target.member(), style -> {
                if (field.equals("style")) return style.enableStringPath("style",objectDefault());
                String token = defaultToken(field);
                validateNumber(target,field,Double.parseDouble(token));
                validateCellularChange(style,target,field,Double.parseDouble(token));
                return style.enable(field,token);
            }, false);
        }, true);
    }
    public static byte[] editStyle(String path, byte[] original, NoiseFields.Target target, String value) {
        return editStyle(path, original, Target.noise(target), value);
    }
    public static byte[] editStyle(String path, byte[] original, Target target, String value) {
        authorize(path, target, "style");
        if (value == null || !CHOICES.contains(value)) throw new IllegalArgumentException("Choose a pinned noise style");
        return editContainer(original, target, container -> container.objectEdit(target.member(), style -> style.stringValue("style", value), false), false);
    }
    public static byte[] editText(String path, byte[] original, NoiseFields.Target target, String field, String input) {
        return editText(path, original, Target.noise(target), field, input);
    }
    public static byte[] editText(String path, byte[] original, Target target, String field, String input) {
        authorize(path, target, field);
        if (field.equals("style")) throw new IllegalArgumentException("Use the noise style picker");
        double value = Double.parseDouble(input);
        validateNumber(target,field,value);
        return editContainer(original, target, container -> container.objectEdit(target.member(), style -> {
            validateCellularChange(style,target,field,value);
            return style.number(field,value);
        }, false), false);
    }
    private static void validateNumber(Target target, String field, double value) {
        String min=bound(target,field,"minimum"),max=bound(target,field,"maximum");
        if (!Double.isFinite(value) || (min != null && value < Double.parseDouble(min)) || (max != null && value > Double.parseDouble(max)))
            throw new IllegalArgumentException("Value violates pinned Iris style constraints");
    }
    private static double numericOrDefault(LosslessJson style, String field) {
        String token = style.token(field);
        try { return Double.parseDouble(token == null ? defaultToken(field) : token); }
        catch (NumberFormatException mismatch) { throw new IllegalArgumentException("Stored "+field+" must be numeric",mismatch); }
    }
    private static void validateCellularChange(LosslessJson style, Target target, String field, double value) {
        if (!field.equals("cellularFrequency") && !field.equals("cellularZoom")) return;
        double frequency = field.equals("cellularFrequency") ? value : numericOrDefault(style,"cellularFrequency");
        if (!Double.isFinite(frequency) || frequency < 0)
            throw new IllegalArgumentException("Cellular frequency must be finite and nonnegative");
        if (frequency > 0) {
            double zoom = field.equals("cellularZoom") ? value : numericOrDefault(style,"cellularZoom");
            if (!Double.isFinite(zoom) || zoom <= 0 || !Double.isFinite(1D/zoom))
                throw new IllegalArgumentException("Cellular zoom must be positive and large enough for a finite reciprocal while frequency > 0");
            if (target.strictBounds()) validateNumber(target,"cellularZoom",zoom);
        }
    }
    static byte[] editContainer(byte[] original, Target target, java.util.function.Function<LosslessJson, byte[]> operation, boolean create) {
        if (target.optionalFracture()) {
            Target root=target.root();
            return editContainer(original,root,container -> container.objectEdit(root.member(),style -> editFractureContainer(style,target.fractureDepth()-1,operation),false),false);
        }
        if (target.overlay()) return TerrainOverlays.edit(original,target.overlayIndex(),operation);
        if (target.carving()) return DimensionCarvings.edit(original,target.carvingIndex(),operation);
        if (target.stackBlend()) return DimensionStacks.editBlend(original,operation);
        if (target.decorator()) return BiomeDecorators.editEntry(original,target.decoratorContext().owner(),operation);
        if (target.ore()) { var context=target.oreContext();return context.field().equals("palette") ? OreGenerators.editPalette(original,context.owner(),operation) : OreGenerators.editEntry(original,context.owner(),operation); }
        if (target.objectPalette()) return FloatingObjectPalettes.editPalette(original,target.objectPaletteContext(),operation);
        if (target.placementWarp()) return FloatingPlacementConstraints.editPlacement(original,target.warpContext(),operation);
        if (target.placementDensity()) return FloatingPlacementSettings.editObject(original,target.placementContext(),"densityStyle",operation);
        if (target.floatingBottom()) return FloatingBottomPalettes.editLayer(original,target.floatingBottomContext().entry(),target.floatingBottomContext().layer(),operation);
        if (target.floating()) return FloatingBiomes.editEntry(original,target.floatingContext().index(),operation);
        if (target.slabWall()) return BiomeSlabWall.editObject(original,target.slabWallField(),operation);
        if (target.biomeLayer()) return BiomeLayers.editLayer(original,target.layerTarget().field(),target.layerTarget().index(),operation);
        if (target.dimensionPalette()) return DimensionPalettes.editPalette(original,target.dimensionPaletteField(),operation);
        if (target.deepHeight()) return DeepFluids.editHeight(original,target.deepIndex(),operation);
        if (target.deepPalette()) return DeepFluids.editPalette(original,target.deepIndex(),operation);
        if (target.poolPalette()) return SurfacePools.editPalette(original,target.poolIndex(),operation);
        if (target.profilePalette()) return RiverProfiles.editPalette(original,target.profileIndex(),operation);
        if (target.palette()) return RiverPaletteFields.editPalette(original,target.paletteTarget(),operation);
        if (target.riverRange()) return RiverChannelRanges.editRange(original,target.rangeField(),operation);
        if (target.banks()) return RiverBankFields.editObject(original,operation);
        if (target.module()) return CaveModules.editObject(original,target.moduleIndex(),operation);
        if (target.threshold()) return new LosslessJson(original).objectEdit("caveProfile", profile -> profile.objectEdit("densityThreshold",operation,false),false);
        if (target.cave()) return new LosslessJson(original).objectEdit("caveProfile",operation,false);
        if (target.terrain()) return new LosslessJson(original).objectEdit("terrain3D",operation,false);
        return target.noise() == null ? operation.apply(new LosslessJson(original)) : NoiseFields.editObject(original, target.noise(), operation, create);
    }
    private static byte[] editFractureContainer(LosslessJson style,int depth,java.util.function.Function<LosslessJson,byte[]> operation) {
        return depth==0 ? operation.apply(style) : style.objectEdit("fracture",child -> editFractureContainer(child,depth-1,operation),false);
    }
    static LosslessJson object(LosslessJson noise) {
        return objectAt(noise, "style");
    }
    static LosslessJson object(LosslessJson container, Target target) {
        if ((target.optionalFloating() || target.optionalFracture()) && "null".equals(container.token(target.member()))) return null;
        return objectAt(container, target.member());
    }
    private static LosslessJson objectAt(LosslessJson container, String field) {
        String token = container.token(field);
        return token == null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
}
