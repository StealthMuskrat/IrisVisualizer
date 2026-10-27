package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Floating entry placement collections with exact asset keys; advanced transforms and native placement stay outside the inspector. */
public final class FloatingObjectPlacements {
    public static final List<String> LIST_FIELDS = List.of("extraObjects", "floatingObjects", "topObjectOverrides", "bottomObjectOverrides");
    public static final List<String> EDITABLE = List.of("place", "allowedCollisions", "forbiddenCollisions", "chance", "density", "snow", "mode", "translate");
    public static final List<String> POOLS = List.of("place", "allowedCollisions", "forbiddenCollisions");
    public static final List<String> SCALARS = List.of("chance", "density", "snow");
    public static final List<String> TRANSLATION_FIELDS = List.of("x", "y", "yRandom", "z");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingObjectPlacements.class.getResourceAsStream("/floating-object-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned floating object definitions");DATA.load(input);
            if (!String.join(",",LIST_FIELDS).equals(DATA.getProperty("lists")) || !String.join(",",POOLS).equals(DATA.getProperty("pools")) || !"configured".equals(DATA.getProperty("floating.mode")) || !"perAttemptWithJitter".equals(DATA.getProperty("chance.context"))) throw new IOException("Unsupported floating object policy");
            if (!modes().contains(defaultToken("mode")) || !modes().contains(DATA.getProperty("newFloatingPreset"))) throw new IOException("Unsupported placement mode presets");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void field(String value,List<String> fields) { if (value==null || !fields.contains(value)) throw new IllegalArgumentException("Unsupported floating placement field"); }
    private static void authorize(String path,String list) { field(list,LIST_FIELDS);if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Floating placements are biome-only"); }
    public static String help(String value) { field(value,EDITABLE);return DATA.getProperty(value+".description",""); }
    public static String listHelp(String list) { field(list,LIST_FIELDS);return list.equals("floatingObjects") ? "Placements attempted through the floating-object path. The pinned renderer preserves their configured mode; select FLOATING for free-air placement." : DATA.getProperty(list+".description",""); }
    public static String defaultToken(String value) { field(value,EDITABLE);return DATA.getProperty(value+".default"); }
    public static String constraints(String value) { field(value,SCALARS);return (value.equals("density") ? "Exact integer" : "Finite number")+", minimum "+DATA.getProperty(value+".minimum")+", maximum "+DATA.getProperty(value+".maximum"); }
    public static List<String> modes() { return List.of(DATA.getProperty("mode.choices").split(",")); }
    public static String modeHelp(String value) { if (value==null || !modes().contains(value)) throw new IllegalArgumentException("Unknown placement mode");return DATA.getProperty("mode."+value+".description",""); }
    public static String creationMode(String list) { field(list,LIST_FIELDS);return list.equals("floatingObjects") ? DATA.getProperty("newFloatingPreset") : defaultToken("mode"); }
    public static String listPrefix(int entry,String list) { field(list,LIST_FIELDS);return FloatingBiomes.entryPrefix(entry)+"objects#"+list+"."; }
    public static String placementPrefix(int entry,String list,int placement) { if (placement<0) throw new IllegalArgumentException("Select a placement");return listPrefix(entry,list)+placement+"."; }
    public static String poolPrefix(int entry,String list,int placement,String pool) { field(pool,POOLS);return placementPrefix(entry,list,placement)+"pool#"+pool+"."; }
    public static List<String> objectChoices(PackWorkspace workspace) {
        return workspace.paths().stream().filter(path -> "objects".equals(ResourceTypes.fileFamily(path)) && path.endsWith(".iob"))
                .map(path -> path.substring("objects/".length(),path.length()-4)).filter(ResourceTypes::referenceKey).sorted().toList();
    }
    private static void object(String key,PackWorkspace workspace) { if (key==null || !objectChoices(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact .iob key present in this pack; target content and loader aliases are unchecked"); }
    static List<LosslessJson> placements(LosslessJson entry,String list) { field(list,LIST_FIELDS);String token=entry.token(list);return token==null || token.equals("null") ? null : entry.objectArray(list); }
    static byte[] editPlacement(byte[] original,int entry,String list,int placement,Function<LosslessJson,byte[]> action) { field(list,LIST_FIELDS);return FloatingBiomes.editEntry(original,entry,e -> e.objectArrayEdit(list,placement,action)); }
    public static byte[] enable(String path,byte[] original,int entry,String list) { authorize(path,list);return FloatingBiomes.editEntry(original,entry,e -> e.enableArray(list)); }
    public static byte[] change(String path,byte[] original,int entry,String list,RiverProfiles.Action action,int index,String key,String mode,PackWorkspace workspace) {
        authorize(path,list);if (action==null) throw new IllegalArgumentException("Choose a placement action");
        if (action==RiverProfiles.Action.ADD) {
            object(key,workspace);if (mode==null || !modes().contains(mode)) throw new IllegalArgumentException("Choose a pinned placement mode");
            byte[] seeded=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enable("place","[]");seeded=new LosslessJson(seeded).stringArrayPath("place",0,key);seeded=new LosslessJson(seeded).enableStringPath("mode",mode);byte[] placement=seeded;
            return FloatingBiomes.editEntry(original,entry,e -> e.objectArrayInsert(list,e.objectArray(list).size(),placement));
        }
        return FloatingBiomes.editEntry(original,entry,e -> BiomeLayers.changeLayers(e,list,action,index));
    }
    public static byte[] enableScalar(String path,byte[] original,int entry,String list,int placement,String value) { authorize(path,list);field(value,SCALARS);return editPlacement(original,entry,list,placement,p -> p.enable(value,defaultToken(value))); }
    public static byte[] setScalar(String path,byte[] original,int entry,String list,int placement,String value,String input) {
        authorize(path,list);field(value,SCALARS);double parsed;
        try { parsed=value.equals("density") ? new BigDecimal(input).intValueExact() : Double.parseDouble(input); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a valid "+(value.equals("density") ? "exact signed 32-bit integer" : "finite number"),invalid); }
        if (!Double.isFinite(parsed) || parsed<Double.parseDouble(DATA.getProperty(value+".minimum")) || parsed>Double.parseDouble(DATA.getProperty(value+".maximum"))) throw new IllegalArgumentException("Value violates pinned placement bounds");double result=parsed;
        return editPlacement(original,entry,list,placement,p -> p.number(value,result));
    }
    public static byte[] setMode(String path,byte[] original,int entry,String list,int placement,String mode) {
        authorize(path,list);if (mode==null || !modes().contains(mode)) throw new IllegalArgumentException("Choose a pinned placement mode");return editPlacement(original,entry,list,placement,p -> p.token("mode")==null ? p.enableStringPath("mode",mode) : p.stringValue("mode",mode));
    }
    static List<String> pool(LosslessJson placement,String value) { field(value,POOLS);return placement.token(value)==null ? null : placement.stringArrayPath(value); }
    public static byte[] enablePool(String path,byte[] original,int entry,String list,int placement,String value) { authorize(path,list);field(value,POOLS);return editPlacement(original,entry,list,placement,p -> p.enable(value,"[]")); }
    public static byte[] setReference(String path,byte[] original,int entry,String list,int placement,String value,int index,String key,PackWorkspace workspace) {
        authorize(path,list);field(value,POOLS);object(key,workspace);return editPlacement(original,entry,list,placement,p -> { var pool=p.stringArrayPath(value);if (index<0 || index>=pool.size()) throw new IllegalArgumentException("Select an existing reference");return p.stringArrayPath(value,index,key); });
    }
    public static byte[] changePool(String path,byte[] original,int entry,String list,int placement,String value,RiverProfiles.Action action,int index,String key,PackWorkspace workspace) {
        authorize(path,list);field(value,POOLS);if (action==null) throw new IllegalArgumentException("Choose a reference action");if (action==RiverProfiles.Action.ADD) object(key,workspace);
        return editPlacement(original,entry,list,placement,p -> {
            var pool=p.stringArrayPath(value);if (action==RiverProfiles.Action.ADD) return p.stringArrayPath(value,pool.size(),key);
            if (index<0 || index>=pool.size()) throw new IllegalArgumentException("Select an existing reference");
            return switch(action) {
                case DUPLICATE -> p.stringArrayDuplicate(value,index);
                case REMOVE -> { if (pool.size()<=Integer.parseInt(DATA.getProperty(value+".minimum"))) throw new IllegalArgumentException("Keep at least one object in the required place pool");yield p.stringArrayPath(value,index,null); }
                case UP -> p.stringArrayMove(value,index,index-1);
                case DOWN -> p.stringArrayMove(value,index,index+1);
                default -> throw new IllegalArgumentException("Unsupported reference action");
            };
        });
    }
    private static void translation(String path,String list,String axis) { authorize(path,list);if (!list.equals("floatingObjects")) throw new IllegalArgumentException("Anchored translations stay read-only");if (axis!=null) field(axis,TRANSLATION_FIELDS); }
    public static String translationHelp(String axis) { field(axis,TRANSLATION_FIELDS);return DATA.getProperty("translate."+axis+".description",""); }
    public static String translationDefault(String axis) { field(axis,TRANSLATION_FIELDS);return DATA.getProperty("translate."+axis+".default"); }
    public static String translationConstraints(String axis) { field(axis,TRANSLATION_FIELDS);return "Exact integer, minimum "+DATA.getProperty("translate."+axis+".minimum")+", maximum "+DATA.getProperty("translate."+axis+".maximum"); }
    static LosslessJson translate(LosslessJson placement) { String token=placement.token("translate");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    public static byte[] createTranslation(String path,byte[] original,int entry,String list,int placement) {
        translation(path,list,null);return editPlacement(original,entry,list,placement,p -> { if (p.token("translate")!=null) throw new IllegalArgumentException("Translation already exists or is unsupported");return p.enableObject("translate"); });
    }
    public static byte[] enableTranslation(String path,byte[] original,int entry,String list,int placement,String axis) {
        translation(path,list,axis);return editPlacement(original,entry,list,placement,p -> p.objectEdit("translate",tr -> tr.enable(axis,translationDefault(axis)),false));
    }
    public static byte[] setTranslation(String path,byte[] original,int entry,String list,int placement,String axis,String input) {
        translation(path,list,axis);int value;try { value=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact translation integer",invalid); }
        if (value<Integer.parseInt(DATA.getProperty("translate."+axis+".minimum")) || value>Integer.parseInt(DATA.getProperty("translate."+axis+".maximum"))) throw new IllegalArgumentException("Translation violates source bounds");
        return editPlacement(original,entry,list,placement,p -> p.objectEdit("translate",tr -> tr.number(axis,value),false));
    }
    static List<String> readOnlyFields(LosslessJson placement,String list) { field(list,LIST_FIELDS);return placement.fields().stream().filter(value -> !EDITABLE.contains(value) || value.equals("translate") && !list.equals("floatingObjects")).toList(); }

}
