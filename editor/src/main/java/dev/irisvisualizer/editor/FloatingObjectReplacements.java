package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Ordered object block-replacement rules; matching and block resolution stay native. */
public final class FloatingObjectReplacements {
    public static final List<String> LIST_FIELDS = List.of("edit");
    public static final List<String> EDITABLE = List.of("find", "replace", "exact", "chance");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingObjectReplacements.class.getResourceAsStream("/floating-object-materials.properties")) {
            if (input==null) throw new IOException("Missing pinned object materials");DATA.load(input);
            if (!RiverPaletteFields.styleDefault().equals(DATA.getProperty("palette.style")) || Double.parseDouble(RiverPaletteFields.zoomDefault())!=Double.parseDouble(DATA.getProperty("palette.zoom.default")) || !RiverPaletteFields.zoomMinimum().equals(DATA.getProperty("palette.zoom.minimum")) || !DimensionPalettes.weightDefault().equals(DATA.getProperty("weight.default")) || DimensionPalettes.maximumWeight()!=Integer.parseInt(DATA.getProperty("weight.maximum"))) throw new IOException("Shared palette definitions differ");
            if (!"1".equals(DATA.getProperty("find.minimum")) || !"emptyDisables".equals(DATA.getProperty("edit.policy")) || !DimensionPalettes.classBlock().equals(DATA.getProperty("palette.block"))) throw new IOException("Unsupported replacement policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int entry,String list,int placement) {
        public Context { if (!FloatingObjectPlacements.validContext(entry,list,placement)) throw new IllegalArgumentException("Select a biome object placement"); }
        public String prefix() { return FloatingObjectPlacements.placementPrefix(entry,list,placement)+"edit#"; }
        public String rulePrefix(int rule) { if (rule<0) throw new IllegalArgumentException("Select a rule");return prefix()+rule+"."; }
        public String findPrefix(int rule) { return rulePrefix(rule)+"find#"; }
    }
    private static void authorize(String path,Context context) { FloatingObjectPlacements.authorize(path,context.entry(),context.list()); }
    public static String definition(String field) { return DATA.getProperty(field); }
    static byte[] editPlacement(byte[] original,Context context,java.util.function.Function<LosslessJson,byte[]> action) { return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),action); }
    static byte[] editRule(byte[] original,Context context,int rule,java.util.function.Function<LosslessJson,byte[]> action) { return editPlacement(original,context,p -> p.objectArrayEdit("edit",rule,action)); }
    static List<LosslessJson> rules(LosslessJson placement) { return placement.token("edit")==null ? null : placement.objectArray("edit"); }
    static List<LosslessJson> find(LosslessJson rule) { return rule.token("find")==null ? null : rule.objectArray("find"); }
    public static byte[] enable(String path,byte[] original,Context context) { authorize(path,context);return editPlacement(original,context,p -> p.enable("edit","[]")); }
    private static void block(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank block; registry/state and alias validation is pending"); }
    private static byte[] findEntry(String value) { block(value);return new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("block",value); }
    public static byte[] change(String path,byte[] original,Context context,RiverProfiles.Action action,int rule,String firstBlock) {
        authorize(path,context);if (action==null) throw new IllegalArgumentException("Choose a replacement action");
        if (action==RiverProfiles.Action.ADD) { byte[] entry=findEntry(firstBlock);byte[] seeded=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enable("find","[]");byte[] replacement=new LosslessJson(seeded).objectArrayInsert("find",0,entry);return editPlacement(original,context,p -> p.objectArrayInsert("edit",p.objectArray("edit").size(),replacement)); }
        return editPlacement(original,context,p -> BiomeLayers.changeLayers(p,"edit",action,rule));
    }
    public static byte[] enableExact(String path,byte[] original,Context context,int rule) { authorize(path,context);return editRule(original,context,rule,r -> r.enable("exact",DATA.getProperty("exact.default"))); }
    public static byte[] setExact(String path,byte[] original,Context context,int rule,boolean value) { authorize(path,context);return editRule(original,context,rule,r -> r.bool("exact",value)); }
    public static byte[] enableChance(String path,byte[] original,Context context,int rule) { authorize(path,context);return editRule(original,context,rule,r -> r.enable("chance",DATA.getProperty("chance.default"))); }
    public static byte[] setChance(String path,byte[] original,Context context,int rule,String input) {
        authorize(path,context);double value;try { value=Double.parseDouble(input); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite replacement chance",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty("chance.minimum")) || value>Double.parseDouble(DATA.getProperty("chance.maximum"))) throw new IllegalArgumentException("Chance must be within pinned bounds");
        return editRule(original,context,rule,r -> r.number("chance",value));
    }
    public static byte[] enableFind(String path,byte[] original,Context context,int rule) { authorize(path,context);return editRule(original,context,rule,r -> r.enable("find","[]")); }
    public static byte[] setFindBlock(String path,byte[] original,Context context,int rule,int index,String value) { authorize(path,context);block(value);return editRule(original,context,rule,r -> r.objectArrayEdit("find",index,f -> f.token("block")==null ? f.enableStringPath("block",value) : f.stringValue("block",value))); }
    public static byte[] changeFind(String path,byte[] original,Context context,int rule,RiverProfiles.Action action,int index,String value) {
        authorize(path,context);if (action==null) throw new IllegalArgumentException("Choose a find-block action");byte[] added=action==RiverProfiles.Action.ADD ? findEntry(value) : null;
        return editRule(original,context,rule,r -> {
            var entries=r.objectArray("find");if (action==RiverProfiles.Action.ADD) return r.objectArrayInsert("find",entries.size(),added);
            if (action==RiverProfiles.Action.REMOVE && entries.size()<=Integer.parseInt(DATA.getProperty("find.minimum"))) throw new IllegalArgumentException("Keep one block in the required find list, or remove the rule");
            return BiomeLayers.changeLayers(r,"find",action,index);
        });
    }
}
