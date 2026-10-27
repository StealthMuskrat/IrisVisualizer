package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Inline biome decorations; placement, sampling and native material rules remain unevaluated. */
public final class BiomeDecorators {
    public static final List<String> LIST_FIELDS = List.of("decorators");
    public static final List<String> EDITABLE = List.of("variance", "forcePlace", "forceBlock", "whitelist", "blacklist", "slopeCondition", "absoluteMaxStack", "style", "heightVariance", "partOf", "stackMin", "stackMax", "scaleStack", "chance", "palette", "topPalette", "topThreshold");
    public static final List<String> STYLES = List.of("variance", "style", "heightVariance");
    public static final List<String> BLOCK_LISTS = List.of("palette", "topPalette", "whitelist", "blacklist");
    public static final List<String> SCALARS = List.of("chance", "topThreshold", "absoluteMaxStack");
    public static final List<String> FLAGS = List.of("forcePlace", "scaleStack");
    private static final Properties DATA=new Properties();
    static {
        try (var input=BiomeDecorators.class.getResourceAsStream("/biome-decorator-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned decorator definitions");DATA.load(input);
            if (!"weightedMainAndTop".equals(DATA.getProperty("palette.policy")) || !"nullUnrestrictedEmptyWhitelistForbid".equals(DATA.getProperty("filters.policy")) || !"reservoirAfterChance".equals(DATA.getProperty("selection.policy"))) throw new IOException("Unsupported decorator policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int index) {
        public Context { if (index<0) throw new IllegalArgumentException("Select a decorator"); }
        public String prefix() { return listPrefix()+index+"."; }
        public String blocksPrefix(String field) { list(field);return prefix()+field+"#."; }
        public NoiseStyleFields.Target styleTarget(String field) { style(field);return new NoiseStyleFields.Target("biomes","decorators."+index+"."+field,null); }
    }
    public record StyleContext(Context owner,String field) { public StyleContext { java.util.Objects.requireNonNull(owner);style(field); } }
    static StyleContext styleContext(String base,String path) {
        if (!"biomes".equals(base) || path==null) return null;var match=java.util.regex.Pattern.compile("decorators\\.(0|[1-9][0-9]*)\\.(variance|style|heightVariance)").matcher(path);
        if (!match.matches()) return null;try { return new StyleContext(new Context(Integer.parseInt(match.group(1))),match.group(2)); } catch (NumberFormatException invalid) { return null; }
    }
    public static String listPrefix() { return "biomeDecorators#."; }
    private static void authorize(String path) { if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Decorators belong to biome resources"); }
    private static void field(String name) { if (name==null || !EDITABLE.contains(name)) throw new IllegalArgumentException("Unsupported decorator field"); }
    private static void list(String name) { if (name==null || !BLOCK_LISTS.contains(name)) throw new IllegalArgumentException("Unsupported decorator block list"); }
    private static void style(String name) { if (name==null || !STYLES.contains(name)) throw new IllegalArgumentException("Unsupported decorator style"); }
    public static boolean filter(String name) { list(name);return name.equals("whitelist") || name.equals("blacklist"); }
    public static boolean weighted(String name) { return !filter(name); }
    public static String help(String name) { field(name);return DATA.getProperty(name+".description",""); }
    public static String defaultToken(String name) { field(name);return DATA.getProperty(name+".default"); }
    public static String defaultBlock() { return DATA.getProperty("palette.defaultBlock"); }
    public static String styleDefault() { return DATA.getProperty("style.default"); }
    public static List<String> parts() { return List.of(DATA.getProperty("partOf.choices").split(",")); }
    public static String constraints(String name) { field(name);return (name.equals("absoluteMaxStack") ? "Editor subset: exact integer" : "Finite number")+", "+DATA.getProperty(name+".minimum")+".."+DATA.getProperty(name+".maximum"); }
    static List<LosslessJson> entries(LosslessJson root) { return root.token("decorators")==null ? null : root.objectArray("decorators"); }
    static byte[] editEntry(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return new LosslessJson(original).objectArrayEdit("decorators",context.index(),action); }
    static List<LosslessJson> blocks(LosslessJson decorator,String field) { list(field);String token=decorator.token(field);return token==null || filter(field) && token.equals("null") ? null : decorator.objectArray(field); }
    private static void block(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter nonblank block text; registry/state validity is unchecked"); }
    private static byte[] blockEntry(String value) { block(value);return new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("block",value); }
    public static byte[] enable(String path,byte[] original) { authorize(path);return new LosslessJson(original).enable("decorators","[]"); }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String material) {
        authorize(path);var root=new LosslessJson(original);
        if (action==RiverProfiles.Action.ADD) { byte[] seed=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enable("palette","[]");seed=new LosslessJson(seed).objectArrayInsert("palette",0,blockEntry(material));return root.objectArrayInsert("decorators",root.objectArray("decorators").size(),seed); }
        return BiomeLayers.changeLayers(root,"decorators",action,index);
    }
    public static byte[] enableScalar(String path,byte[] original,Context context,String field) {
        authorize(path);if (!SCALARS.contains(field) && !FLAGS.contains(field)) throw new IllegalArgumentException("Use the typed decorator control");return editEntry(original,context,d -> d.enable(field,defaultToken(field)));
    }
    public static byte[] setFlag(String path,byte[] original,Context context,String field,boolean value) { authorize(path);if (!FLAGS.contains(field)) throw new IllegalArgumentException("Select a decorator flag");return editEntry(original,context,d -> d.bool(field,value)); }
    private static int integer(String input) { try { return new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); } }
    private static double number(String field,String input,boolean integral) {
        double value;try { value=integral ? integer(input) : Double.parseDouble(input); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter finite numbers",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(field+".minimum")) || value>Double.parseDouble(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Value violates decorator bounds");return value;
    }
    public static byte[] setScalar(String path,byte[] original,Context context,String field,String input) { authorize(path);if (!SCALARS.contains(field)) throw new IllegalArgumentException("Use the paired stack or typed control");double value=number(field,input,field.equals("absoluteMaxStack"));return editEntry(original,context,d -> d.number(field,value)); }
    private static byte[] bound(LosslessJson d,String field,double value) { return d.token(field)==null ? d.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : d.number(field,value); }
    public static byte[] setStack(String path,byte[] original,Context context,String minimum,String maximum) {
        authorize(path);double min=number("stackMin",minimum,true),max=number("stackMax",maximum,true);if (min>max) throw new IllegalArgumentException("Minimum stack must not exceed maximum stack");return editEntry(original,context,d -> bound(new LosslessJson(bound(d,"stackMin",min)),"stackMax",max));
    }
    public static byte[] setPart(String path,byte[] original,Context context,String value) { authorize(path);if (value==null || !parts().contains(value)) throw new IllegalArgumentException("Choose a pinned decoration part");return editEntry(original,context,d -> d.token("partOf")==null ? d.enableStringPath("partOf",value) : d.stringValue("partOf",value)); }
    public static byte[] enableBlocks(String path,byte[] original,Context context,String field) {
        authorize(path);list(field);return editEntry(original,context,d -> {
            if (filter(field)) { String token=d.token(field);if (token!=null && !token.equals("null")) throw new IllegalArgumentException("Filter already exists or is unsupported");return d.enableArray(field); }
            byte[] next=d.enable(field,"[]");return field.equals("palette") ? new LosslessJson(next).objectArrayInsert(field,0,blockEntry(defaultBlock())) : next;
        });
    }
    public static byte[] restoreFilter(String path,byte[] original,Context context,String field) { authorize(path);if (!filter(field)) throw new IllegalArgumentException("Only optional filters can be null");return editEntry(original,context,d -> d.nullableObjectArray(field,true)); }
    public static byte[] setBlock(String path,byte[] original,Context context,String field,int index,String value) { authorize(path);list(field);return editEntry(original,context,d -> GeneralBlockPalettes.setBlock(d,field,index,value)); }
    public static byte[] changeBlock(String path,byte[] original,Context context,String field,RiverProfiles.Action action,int index,String value) { authorize(path);list(field);return editEntry(original,context,d -> GeneralBlockPalettes.change(d,field,action,index,value,field.equals("palette") ? 1 : 0)); }
    public static byte[] enableWeight(String path,byte[] original,Context context,String field,int index) { authorize(path);if (!weighted(field)) throw new IllegalArgumentException("Host filters do not consume weights");return editEntry(original,context,d -> GeneralBlockPalettes.enableWeight(d,field,index)); }
    public static byte[] setWeight(String path,byte[] original,Context context,String field,int index,String value) { authorize(path);if (!weighted(field)) throw new IllegalArgumentException("Host filters do not consume weights");return editEntry(original,context,d -> GeneralBlockPalettes.setWeight(d,field,index,value)); }
    static LosslessJson forceBlock(LosslessJson d) { String token=d.token("forceBlock");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    public static byte[] setForceBlock(String path,byte[] original,Context context,String value) {
        authorize(path);block(value);return editEntry(original,context,d -> { String token=d.token("forceBlock");if (token==null || token.equals("null")) return new LosslessJson(d.enableObject("forceBlock")).objectEdit("forceBlock",b -> b.enableStringPath("block",value),false);return d.objectEdit("forceBlock",b -> b.token("block")==null ? b.enableStringPath("block",value) : b.stringValue("block",value),false); });
    }
    public static byte[] restoreForceBlock(String path,byte[] original,Context context) { authorize(path);return editEntry(original,context,d -> d.nullObject("forceBlock")); }
    public static byte[] enableSlope(String path,byte[] original,Context context) { authorize(path);return editEntry(original,context,d -> { if (d.token("slopeCondition")!=null) throw new IllegalArgumentException("Slope already exists or is unsupported");return d.enableObject("slopeCondition"); }); }
    public static byte[] setSlope(String path,byte[] original,Context context,String minimum,String maximum) { authorize(path);return editEntry(original,context,d -> BiomeLayers.slopeBounds(d,minimum,maximum)); }
}
