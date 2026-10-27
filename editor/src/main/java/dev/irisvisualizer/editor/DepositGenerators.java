package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Lossless deposit authoring; native clump geometry, block resolution and generation remain unavailable. */
public final class DepositGenerators {
    public static final List<String> LIST_FIELDS = List.of("deposits");
    public static final List<String> EDITABLE = List.of("minHeight", "maxHeight", "heightDistribution", "placementScope", "surfaceClearance", "minSize", "maxSize", "shape", "maxPerChunk", "minPerChunk", "spawnChance", "perClumpSpawnChance", "discardChanceOnAirExposure", "palette", "varience", "replaceableBlocks", "surfaceReplaceableBlocks", "biomeScope", "includedBiomes", "excludedBiomes", "replaceBedrock");
    public static final List<String> PAIRS = List.of("Height", "Size", "PerChunk");
    public static final List<String> SCALARS = List.of("surfaceClearance", "spawnChance", "perClumpSpawnChance", "discardChanceOnAirExposure", "varience");
    public static final List<String> ENUMS = List.of("heightDistribution", "placementScope", "shape", "biomeScope");
    public static final List<String> FILTERS = List.of("replaceableBlocks", "surfaceReplaceableBlocks", "includedBiomes", "excludedBiomes");
    private static final Properties DATA=new Properties();
    static {
        try (var input=DepositGenerators.class.getResourceAsStream("/deposit-generator-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned deposit definitions");DATA.load(input);
            if (!String.join(",",EDITABLE).equals(DATA.getProperty("fields")) || !"oreOnlyBiomeOverride".equals(DATA.getProperty("surface.policy")) || !"uniformEntries".equals(DATA.getProperty("palette.policy")) || !"dimensionRegionBiomeAdditive".equals(DATA.getProperty("dispatch.policy")) || !"inclusiveLocalY".equals(DATA.getProperty("height.policy"))) throw new IOException("Unsupported deposit policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(String resourceBase,int index) {
        public Context { if (resourceBase==null || !OreGenerators.CONTEXTS.contains(resourceBase) || index<0) throw new IllegalArgumentException("Select a deposit generator"); }
        public String prefix() { return listPrefix(resourceBase)+index+"."; }
        public String prefix(String field) { field(field);return prefix()+field+"."; }
        public String palettePrefix() { return prefix("palette")+"entries#"; }
        public String filterPrefix(String field) { filterField(field);return prefix(field)+"entries#"; }
    }
    private static void field(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported deposit field"); }
    private static void authorize(String path,String base) { if (base==null || !OreGenerators.CONTEXTS.contains(base) || !base.equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Deposit owner does not match this resource"); }
    private static void authorize(String path,Context context) { authorize(path,context.resourceBase()); }
    public static String listPrefix(String base) { if (base==null || !OreGenerators.CONTEXTS.contains(base)) throw new IllegalArgumentException("Unsupported deposit owner");return "depositGenerators#"+base+"."; }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) { field(field);return DATA.getProperty(field+".default"); }
    public static String constraints(String field) { field(field);if (DATA.getProperty(field+".minimum")==null) throw new IllegalArgumentException("Not a numeric deposit field");return (integer(field) ? "Exact signed 32-bit integer" : "Finite number")+", minimum "+DATA.getProperty(field+".minimum")+", maximum "+DATA.getProperty(field+".maximum"); }
    public static boolean integer(String field) { field(field);return "int".equals(DATA.getProperty(field+".type")); }
    public static List<String> choices(String field) { if (field==null || !ENUMS.contains(field)) throw new IllegalArgumentException("Select a deposit enum");return List.of(DATA.getProperty(field+".choices").split(",")); }
    public static String choiceHelp(String field,String value) { if (!choices(field).contains(value)) throw new IllegalArgumentException("Select a pinned deposit choice");return DATA.getProperty(field+"."+value+".description",""); }
    public static String pairField(String pair,boolean minimum) { if (pair==null || !PAIRS.contains(pair)) throw new IllegalArgumentException("Select a deposit range");return (minimum ? "min" : "max")+pair; }
    static List<LosslessJson> entries(LosslessJson root) { return root.token("deposits")==null ? null : root.objectArray("deposits"); }
    private static byte[] edit(byte[] original,Context context,java.util.function.Function<LosslessJson,byte[]> action) { return new LosslessJson(original).objectArrayEdit("deposits",context.index(),action); }
    public static byte[] enable(String path,byte[] original,String base) { authorize(path,base);return new LosslessJson(original).enable("deposits","[]"); }
    public static byte[] change(String path,byte[] original,String base,RiverProfiles.Action action,int index,String block) {
        authorize(path,base);if (action==null) throw new IllegalArgumentException("Choose a deposit action");var root=new LosslessJson(original);
        if (action==RiverProfiles.Action.ADD) return root.objectArrayInsert("deposits",root.objectArray("deposits").size(),GeneralBlockPalettes.seed(new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)),block));
        return BiomeLayers.changeLayers(root,"deposits",action,index);
    }
    private static double number(String field,String input) {
        double value;
        try { value=integer(field) ? new BigDecimal(input).intValueExact() : Double.parseDouble(input); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a valid "+(integer(field) ? "exact signed 32-bit integer" : "finite number"),invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(field+".minimum")) || value>Double.parseDouble(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Value violates pinned deposit bounds");return value;
    }
    private static byte[] numeric(LosslessJson entry,String field,double value) { return entry.token(field)==null ? entry.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : entry.number(field,value); }
    public static byte[] setPair(String path,byte[] original,Context context,String pair,String minimum,String maximum) {
        authorize(path,context);String low=pairField(pair,true),high=pairField(pair,false);double min=number(low,minimum),max=number(high,maximum);
        if (min>max) throw new IllegalArgumentException("Minimum must be at most maximum");
        return edit(original,context,e -> numeric(new LosslessJson(numeric(e,low,min)),high,max));
    }
    public static byte[] enableScalar(String path,byte[] original,Context context,String field) { authorize(path,context);if (field==null || !SCALARS.contains(field)) throw new IllegalArgumentException("Use paired ranges or the dedicated field controls");return edit(original,context,e -> e.enable(field,defaultToken(field))); }
    public static byte[] setScalar(String path,byte[] original,Context context,String field,String input) { authorize(path,context);if (field==null || !SCALARS.contains(field)) throw new IllegalArgumentException("Use paired ranges or the dedicated field controls");double value=number(field,input);return edit(original,context,e -> e.number(field,value)); }
    public static byte[] setChoice(String path,byte[] original,Context context,String field,String choice) { authorize(path,context);if (choice==null || !choices(field).contains(choice)) throw new IllegalArgumentException("Select a pinned deposit choice");return edit(original,context,e -> e.token(field)==null ? e.enableStringPath(field,choice) : e.stringValue(field,choice)); }
    public static byte[] enableBedrock(String path,byte[] original,Context context) { authorize(path,context);return edit(original,context,e -> e.enable("replaceBedrock",defaultToken("replaceBedrock"))); }
    public static byte[] setBedrock(String path,byte[] original,Context context,boolean value) { authorize(path,context);return edit(original,context,e -> e.bool("replaceBedrock",value)); }
    static List<LosslessJson> palette(LosslessJson entry) { return entry.token("palette")==null ? null : entry.objectArray("palette"); }
    public static byte[] enablePalette(String path,byte[] original,Context context) { authorize(path,context);return edit(original,context,e -> e.enable("palette","[]")); }
    public static byte[] setBlock(String path,byte[] original,Context context,int index,String value) { authorize(path,context);return edit(original,context,e -> GeneralBlockPalettes.setBlock(e,index,value)); }
    public static byte[] changePalette(String path,byte[] original,Context context,RiverProfiles.Action action,int index,String value) { authorize(path,context);return edit(original,context,e -> GeneralBlockPalettes.change(e,action,index,value,1)); }
    private static void filterField(String field) { if (field==null || !FILTERS.contains(field)) throw new IllegalArgumentException("Select a deposit filter"); }
    public static boolean biomeFilter(String field) { filterField(field);return field.equals("includedBiomes") || field.equals("excludedBiomes"); }
    static List<String> filter(LosslessJson entry,String field) { filterField(field);return entry.token(field)==null ? null : entry.stringArrayPath(field); }
    private static void filterValue(String field,String value,PackWorkspace workspace) {
        filterField(field);if (value==null) throw new IllegalArgumentException("Enter a filter value");
        if (biomeFilter(field)) {
            if (workspace!=null && ResourceTypes.referenceKeys(workspace,"biomes").contains(value) || value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return;
            throw new IllegalArgumentException("Choose an exact in-pack biome key or a namespaced derivative id; registry existence is unchecked");
        }
        if (!value.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) throw new IllegalArgumentException("Enter a literal block id without states; registry existence is unchecked");
    }
    public static byte[] enableFilter(String path,byte[] original,Context context,String field) { authorize(path,context);filterField(field);return edit(original,context,e -> e.enable(field,"[]")); }
    public static byte[] setFilter(String path,byte[] original,Context context,String field,int index,String value,PackWorkspace workspace) {
        authorize(path,context);filterValue(field,value,workspace);return edit(original,context,e -> { var values=e.stringArrayPath(field);if (index<0 || index>=values.size()) throw new IllegalArgumentException("Select an existing filter");return e.stringArrayPath(field,index,value); });
    }
    public static byte[] changeFilter(String path,byte[] original,Context context,String field,RiverProfiles.Action action,int index,String value,PackWorkspace workspace) {
        authorize(path,context);filterField(field);if (action==null) throw new IllegalArgumentException("Choose a filter action");if (action==RiverProfiles.Action.ADD) filterValue(field,value,workspace);
        return edit(original,context,e -> {
            var values=e.stringArrayPath(field);if (action==RiverProfiles.Action.ADD) return e.stringArrayPath(field,values.size(),value);
            if (index<0 || index>=values.size()) throw new IllegalArgumentException("Select an existing filter");
            return switch(action) {
                case DUPLICATE -> e.stringArrayDuplicate(field,index);
                case REMOVE -> e.stringArrayPath(field,index,null);
                case UP -> e.stringArrayMove(field,index,index-1);
                case DOWN -> e.stringArrayMove(field,index,index+1);
                default -> throw new IllegalArgumentException("Unsupported filter action");
            };
        });
    }
}
