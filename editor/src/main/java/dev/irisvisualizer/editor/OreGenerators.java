package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Ordered native single-block ore generators; authoring never samples noise or resolves blocks. */
public final class OreGenerators {
    public static final List<String> CONTEXTS = List.of("dimensions", "regions", "biomes");
    public static final List<String> LIST_FIELDS = List.of("ores");
    public static final List<String> EDITABLE = List.of("palette", "chanceStyle", "generateSurface", "threshold", "range");
    private static final Properties DATA=new Properties();
    static {
        try (var input=OreGenerators.class.getResourceAsStream("/ore-generator-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned ore definitions");DATA.load(input);
            if (!"exclusivePassLists".equals(DATA.getProperty("surface.policy")) || !"empty".equals(DATA.getProperty("palette.contextDefault")) || !"inclusiveLocalY".equals(DATA.getProperty("range.policy")) || !"firstNonNull".equals(DATA.getProperty("order.policy"))) throw new IOException("Unsupported ore policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(String resourceBase,int index) {
        public Context { if (resourceBase==null || !CONTEXTS.contains(resourceBase) || index<0) throw new IllegalArgumentException("Select an ore generator"); }
        public String prefix() { return listPrefix(resourceBase)+index+"."; }
        public String prefix(String field) { field(field);return prefix()+field+"."; }
        public GeneralBlockPalettes.Target paletteTarget() { return GeneralBlockPalettes.Target.ore(this); }
        public NoiseStyleFields.Target styleTarget(String field) { return NoiseStyleFields.Target.ore(this,field); }
    }
    record StyleContext(Context owner,String field) {
        String member() { return field.equals("palette") ? "style" : "chanceStyle"; }
        String prefix() { return owner.prefix(field)+(field.equals("palette") ? "noise.style." : ""); }
    }
    private static void field(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported ore field"); }
    private static void context(String base) { if (base==null || !CONTEXTS.contains(base)) throw new IllegalArgumentException("Unsupported ore owner"); }
    private static void authorize(String path,String base) { context(base);if (!base.equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Ore owner does not match this resource"); }
    private static void authorize(String path,Context context) { authorize(path,context.resourceBase()); }
    public static String listPrefix(String base) { context(base);return "oreGenerators#"+base+"."; }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) { field(field);return DATA.getProperty(field+".default"); }
    public static String styleDefault() { return DATA.getProperty("chanceStyle.default"); }
    public static String rangeDefault(String member,boolean existing) { if (!List.of("min","max").contains(member)) throw new IllegalArgumentException("Select a range bound");return DATA.getProperty((existing ? "range.class." : "range.context.")+member); }
    static List<LosslessJson> entries(LosslessJson root) { return root.token("ores")==null ? null : root.objectArray("ores"); }
    static byte[] editEntry(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return new LosslessJson(original).objectArrayEdit("ores",context.index(),action); }
    static LosslessJson object(LosslessJson ore,String field) { if (!List.of("palette","range").contains(field)) throw new IllegalArgumentException("Select an ore object");String token=ore.token(field);return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    public static byte[] enable(String path,byte[] original,String base) { authorize(path,base);return new LosslessJson(original).enable("ores","[]"); }
    public static byte[] change(String path,byte[] original,String base,RiverProfiles.Action action,int index,String block) {
        authorize(path,base);if (action==null) throw new IllegalArgumentException("Choose an ore action");var root=new LosslessJson(original);
        if (action==RiverProfiles.Action.ADD) {
            if (block==null || block.isBlank()) throw new IllegalArgumentException("Enter a nonblank block; registry/state validation is pending");
            byte[] palette=GeneralBlockPalettes.seed(new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)),block);
            byte[] ore=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).objectEdit("palette",ignored -> palette,true);
            return root.objectArrayInsert("ores",root.objectArray("ores").size(),ore);
        }
        return BiomeLayers.changeLayers(root,"ores",action,index);
    }
    public static byte[] enableScalar(String path,byte[] original,Context context,String field) { authorize(path,context);if (!List.of("threshold","generateSurface").contains(field)) throw new IllegalArgumentException("Select an ore scalar");return editEntry(original,context,o -> o.enable(field,defaultToken(field))); }
    public static byte[] setSurface(String path,byte[] original,Context context,boolean value) { authorize(path,context);return editEntry(original,context,o -> o.bool("generateSurface",value)); }
    private static double finite(String input) { try { double value=Double.parseDouble(input);if (!Double.isFinite(value)) throw new IllegalArgumentException("Enter a finite number");return value; } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite number",invalid); } }
    public static byte[] setThreshold(String path,byte[] original,Context context,String input) { authorize(path,context);double value=finite(input);if (value<0 || value>1) throw new IllegalArgumentException("Threshold must be between 0 and 1");return editEntry(original,context,o -> o.number("threshold",value)); }
    public static byte[] createRange(String path,byte[] original,Context context) {
        authorize(path,context);return editEntry(original,context,o -> { if (o.token("range")!=null) throw new IllegalArgumentException("Range already exists or has an unsupported type");return o.objectEdit("range",ignored -> ("{\"min\":"+rangeDefault("min",false)+",\"max\":"+rangeDefault("max",false)+"}").getBytes(StandardCharsets.UTF_8),true); });
    }
    private static byte[] bound(LosslessJson range,String field,double value) { return range.token(field)==null ? range.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : range.number(field,value); }
    public static byte[] setRange(String path,byte[] original,Context context,String minimum,String maximum) {
        authorize(path,context);double min=finite(minimum),max=finite(maximum);
        if (min < -8192 || max > 8192 || min>max) throw new IllegalArgumentException("Use ordered editor bounds from -8192 to 8192 in engine-local Y");
        return editEntry(original,context,o -> o.objectEdit("range",range -> bound(new LosslessJson(bound(range,"min",min)),"max",max),false));
    }
    static byte[] editPalette(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return editEntry(original,context,o -> o.objectEdit("palette",action,false)); }
    public static byte[] createPalette(String path,byte[] original,Context context) {
        authorize(path,context);return editEntry(original,context,o -> { if (o.token("palette")!=null) throw new IllegalArgumentException("Palette already exists or has an unsupported type");return o.objectEdit("palette",ignored -> "{\"palette\":[]}".getBytes(StandardCharsets.UTF_8),true); });
    }
    public static byte[] enablePaletteEntries(String path,byte[] original,Context context) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.seed(p,DimensionPalettes.classBlock())); }
    public static byte[] setBlock(String path,byte[] original,Context context,int index,String value) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.setBlock(p,index,value)); }
    public static byte[] enableWeight(String path,byte[] original,Context context,int index) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,Context context,int index,String input) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.setWeight(p,index,input)); }
    public static byte[] changePalette(String path,byte[] original,Context context,RiverProfiles.Action action,int index,String value) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.change(p,action,index,value,0)); }
    public static byte[] enableZoom(String path,byte[] original,Context context) { authorize(path,context);return editPalette(original,context,p -> p.enable("zoom",RiverPaletteFields.zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,Context context,String input) { authorize(path,context);double value=RiverPaletteFields.checkedZoom(input);return editPalette(original,context,p -> p.number("zoom",value)); }
    static StyleContext styleContext(String base,String path) {
        if (base==null || !CONTEXTS.contains(base) || path==null) return null;
        var match=java.util.regex.Pattern.compile("ores\\.(0|[1-9][0-9]*)\\.(chanceStyle|palette\\.style)").matcher(path);if (!match.matches()) return null;
        try { return new StyleContext(new Context(base,Integer.parseInt(match.group(1))),match.group(2).equals("chanceStyle") ? "chanceStyle" : "palette"); } catch (NumberFormatException invalid) { return null; }
    }
}
