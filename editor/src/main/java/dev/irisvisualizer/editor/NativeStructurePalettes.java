package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Native adjustment palette data; no block lookup, noise sampling or terrain operation. */
public final class NativeStructurePalettes {
    public static final List<String> STILT_FIELDS = List.of("palette");
    public static final List<String> TERRAIN_FIELDS = List.of("encasePalette");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=NativeStructurePalettes.class.getResourceAsStream("/native-structure-palette-fields.properties")) {
            if (input==null) throw new IOException("Missing native structure palette contracts");DATA.load(input);
            if (!"minecraft:cobblestone".equals(DATA.getProperty("stilt.absent")) || !"environmentFallback".equals(DATA.getProperty("terrain.absent")) || !"STONE".equals(DATA.getProperty("palette.classBlock"))) throw new IOException("Unsupported structure palette defaults");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int index,String owner,String resourceBase,boolean placement) {
        public Context(int index,String owner) { this(index,owner,"dimensions",false); }
        public Context { if (index<0 || owner==null || !List.of("stilt","terrain").contains(owner) || resourceBase==null || (placement ? !StructurePlacements.CONTEXTS.contains(resourceBase) : !resourceBase.equals("dimensions"))) throw new IllegalArgumentException("Select a structure palette in a supported owner"); }
        public static Context placement(String base,int index,String owner) { return new Context(index,owner,base,true); }
        public String member() { return owner.equals("stilt") ? "palette" : "encasePalette"; }
        public String prefix() { return (placement ? StructurePlacements.prefix(index) : NativeStructureAdjustments.prefix(index))+owner+"."+member()+"."; }
        public String stylePath() { return (placement ? "structures." : "importedStructures.adjustments.")+index+"."+owner+"."+member()+".style"; }
        public NoiseStyleFields.Target styleTarget() { return new NoiseStyleFields.Target(resourceBase,stylePath(),null); }
        GeneralBlockPalettes.Target target() { return GeneralBlockPalettes.Target.nativeStructure(this); }
    }
    private static void authorize(String path,Context context) { if (context==null || !context.resourceBase().equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Structure palette owner does not match the resource"); }
    static Context styleContext(String base,String path) {
        if (base==null || path==null) return null;
        boolean placement=path.startsWith("structures.");
        if (placement ? !StructurePlacements.CONTEXTS.contains(base) : !base.equals("dimensions") || !path.startsWith("importedStructures.adjustments.")) return null;
        String prefix=placement ? "structures" : "importedStructures\\.adjustments";
        var match=Pattern.compile(prefix+"\\.(0|[1-9][0-9]*)\\.(stilt\\.palette|terrain\\.encasePalette)\\.style").matcher(path);
        if (!match.matches()) return null;
        try { return new Context(Integer.parseInt(match.group(1)),match.group(2).startsWith("stilt.") ? "stilt" : "terrain",base,placement); } catch (NumberFormatException invalid) { return null; }
    }
    public static String creationBlock(Context context) { return context.owner().equals("stilt") ? DATA.getProperty("stilt.absent") : DATA.getProperty("palette.classBlock"); }
    static LosslessJson object(LosslessJson entry,Context context) { var owner=NativeStructureAdjustments.object(entry,context.owner());if (owner==null) return null;String token=owner.token(context.member());return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] editOwner(byte[] original,Context context,Function<LosslessJson,byte[]> operation) { if (context.placement()) return StructurePlacementIntegration.editOwner(original,context.index(),context.owner(),operation);return NativeStructureAdjustments.edit(original,context.index(),e -> { if (NativeStructureAdjustments.object(e,context.owner())==null) throw new IllegalArgumentException("Enable the inline adjustment owner first");return e.objectEdit(context.owner(),operation,false); }); }
    static List<LosslessJson> entries(LosslessJson palette) { return palette.token("palette")==null ? null : palette.objectArray("palette"); }
    static void checkEntries(LosslessJson palette) { var entries=entries(palette);if (entries!=null && entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Above the 64-entry structure palette editor limit"); }
    static byte[] editPalette(byte[] original,Context context,Function<LosslessJson,byte[]> operation) { return editOwner(original,context,o -> { String token=o.token(context.member());if (token==null || token.equals("null")) throw new IllegalArgumentException("Enable an inline structure palette first");return o.objectEdit(context.member(),p -> { checkEntries(p);return operation.apply(p); },false); }); }
    public static byte[] create(String path,byte[] original,Context context) { authorize(path,context);return editOwner(original,context,o -> { if (o.token(context.member())!=null && !o.token(context.member()).equals("null")) throw new IllegalArgumentException("Palette already exists or has an unsupported type");var enabled=new LosslessJson(o.enableObject(context.member()));return enabled.objectEdit(context.member(),p -> GeneralBlockPalettes.seed(p,creationBlock(context)),false); }); }
    public static byte[] reset(String path,byte[] original,Context context) { authorize(path,context);return editOwner(original,context,o -> { String token=o.token(context.member());if (token==null) return o.sourceBytes();if (!token.equals("null")) checkEntries(new LosslessJson(token.getBytes(StandardCharsets.UTF_8)));return o.removeField(context.member()); }); }
    public static byte[] enableEntries(String path,byte[] original,Context context) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.seed(p,DimensionPalettes.classBlock())); }
    private static void block(String input) { if (input==null || input.isBlank() || input.indexOf(0)>=0) throw new IllegalArgumentException("Enter a nonblank block name without NUL; registry/state/role are unvalidated"); }
    public static byte[] setBlock(String path,byte[] original,Context context,int index,String input) { authorize(path,context);block(input);return editPalette(original,context,p -> p.objectArrayEdit("palette",index,e -> { String old=e.stringToken("block");return input.equals(old) ? e.sourceBytes() : old==null ? e.enableStringPath("block",input) : e.stringValue("block",input); })); }
    private static String weight(LosslessJson entry) { String token=entry.token("weight");if (token==null) return DimensionPalettes.weightDefault();try { if (!LosslessJson.numericToken(token)) throw new NumberFormatException();new BigDecimal(token).intValueExact();return token; } catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored weight is not an exact signed 32-bit integer",invalid); } }
    static boolean supportedWeight(LosslessJson entry) { try { weight(entry);return true; } catch (IllegalArgumentException invalid) { return false; } }
    public static byte[] enableWeight(String path,byte[] original,Context context,int index) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,Context context,int index,String input) {
        authorize(path,context);int value;try { value=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact integer weight",invalid); }
        if (value<DimensionPalettes.minimumWeight() || value>DimensionPalettes.maximumWeight()) throw new IllegalArgumentException("Weight violates native palette bounds");
        return editPalette(original,context,p -> p.objectArrayEdit("palette",index,e -> { weight(e);if (e.token("weight")==null) throw new IllegalArgumentException("Enable weight first");return new BigDecimal(e.token("weight")).intValueExact()==value ? e.sourceBytes() : e.integer("weight",value); }));
    }
    public static byte[] change(String path,byte[] original,Context context,RiverProfiles.Action action,int index,String input) { authorize(path,context);if (action==null) throw new IllegalArgumentException("Choose a palette action");if (action==RiverProfiles.Action.ADD) block(input);return editPalette(original,context,p -> { var entries=entries(p);if (entries==null) throw new IllegalArgumentException("Enable entries first");if ((action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && entries.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Structure palettes have a 64-entry editor limit");return GeneralBlockPalettes.change(p,action,index,input); }); }
    public static byte[] enableZoom(String path,byte[] original,Context context) { authorize(path,context);return editPalette(original,context,p -> p.enable("zoom",RiverPaletteFields.zoomDefault())); }
    private static String zoom(LosslessJson palette) { String token=palette.token("zoom");if (token==null) return RiverPaletteFields.zoomDefault();try { if (!LosslessJson.numericToken(token) || !Double.isFinite(new BigDecimal(token).doubleValue())) throw new NumberFormatException();return token; } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Stored palette zoom is not finite numeric data",invalid); } }
    static boolean supportedZoom(LosslessJson palette) { try { zoom(palette);return true; } catch (IllegalArgumentException invalid) { return false; } }
    public static byte[] setZoom(String path,byte[] original,Context context,String input) {
        authorize(path,context);BigDecimal value;try { value=new BigDecimal(input);if (value.compareTo(new BigDecimal(RiverPaletteFields.zoomMinimum()))<0 || !Double.isFinite(value.doubleValue())) throw new NumberFormatException(); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite palette zoom at least "+RiverPaletteFields.zoomMinimum(),invalid); }
        return editPalette(original,context,p -> { zoom(p);if (p.token("zoom")==null) throw new IllegalArgumentException("Enable zoom first");return new BigDecimal(p.token("zoom")).compareTo(value)==0 ? p.sourceBytes() : p.number("zoom",value.doubleValue()); });
    }
    static List<String> readOnlyPaths(LosslessJson entry,Context context) {
        var paths=new ArrayList<String>();String prefix=context.member();
        try { var palette=object(entry,context);if (palette==null) return List.of();checkEntries(palette);
            for (String field : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(field)) paths.add(prefix+"."+field);
            try { zoom(palette); } catch (IllegalArgumentException invalid) { paths.add(prefix+".zoom"); }
            try { var style=NoiseStyleFields.object(palette,context.styleTarget());if (style!=null) for (String field : StyleFractures.readOnlyPaths(style)) paths.add(prefix+".style."+field); } catch (IllegalArgumentException invalid) { paths.add(prefix+".style"); }
            PaletteBlockData.report(paths,entries(palette),prefix+".palette");
        } catch (IllegalArgumentException invalid) { paths.add(prefix); }return List.copyOf(paths);
    }
}
