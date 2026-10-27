package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Mode-dependent stilt and vacuum settings; native deformation remains outside the editor. */
public final class FloatingPlacementDeformation {
    public static final List<String> OBJECT_FIELDS = List.of("stiltSettings", "vacuumSettings");
    public static final List<String> STILT_FIELDS = List.of("yMax", "yRand", "overStilt", "organicMaxScan", "organicJitter", "organicScratch", "exclude");
    public static final List<String> CLASSIC_FIELDS = List.of("yMax", "yRand", "overStilt");
    public static final List<String> VACUUM_FIELDS = List.of("radius", "falloff", "organicJitter", "waveAmplitude", "waveScale");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingPlacementDeformation.class.getResourceAsStream("/floating-placement-deformation.properties")) {
            if (input==null) throw new IOException("Missing pinned deformation definitions");DATA.load(input);
            if (!"nullFallback".equals(DATA.getProperty("object.policy")) || !"floatingObjectsOnly".equals(DATA.getProperty("classic.context"))) throw new IOException("Unsupported deformation policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int entry,String list,int placement) {
        public Context { if (entry<0 || placement<0 || list==null || !FloatingObjectPlacements.LIST_FIELDS.contains(list)) throw new IllegalArgumentException("Select a floating placement"); }
        public String prefix(String object) { objectField(object);return FloatingObjectPlacements.placementPrefix(entry,list,placement)+object+"."; }
        public String exclusionPrefix() { return prefix("stiltSettings")+"exclude#."; }
    }
    private static void objectField(String field) { if (field==null || !OBJECT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported deformation object"); }
    private static void member(String object,String field) { objectField(object);if (field==null || !(object.equals("stiltSettings") ? STILT_FIELDS : VACUUM_FIELDS).contains(field)) throw new IllegalArgumentException("Unsupported deformation field"); }
    private static void authorize(String path,Context context,String object,String field) { objectField(object);if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Floating deformation is biome-only");if (field!=null) { member(object,field);if (!editable(context.list(),object,field)) throw new IllegalArgumentException("Classic stilt ranges remain read-only in anchored collections"); } }
    public static boolean editable(String list,String object,String field) { member(object,field);return !object.equals("stiltSettings") || !CLASSIC_FIELDS.contains(field) || "floatingObjects".equals(list); }
    public static List<String> scalars(String object) { objectField(object);return object.equals("stiltSettings") ? STILT_FIELDS.stream().filter(field -> !field.equals("exclude")).toList() : VACUUM_FIELDS; }
    public static String help(String object) { objectField(object);return DATA.getProperty(object+".description",""); }
    public static String help(String object,String field) { member(object,field);return DATA.getProperty(object+"."+field+".description",""); }
    public static String defaultToken(String object,String field) { member(object,field);return DATA.getProperty(object+"."+field+".default"); }
    public static String constraints(String object,String field) { scalar(object,field);String key=object+"."+field;return (DATA.getProperty(key+".type").equals("int") ? "Exact integer" : "Finite number")+", minimum "+DATA.getProperty(key+".minimum")+", maximum "+DATA.getProperty(key+".maximum"); }
    private static void scalar(String object,String field) { member(object,field);if (field.equals("exclude")) throw new IllegalArgumentException("Use the material exclusion list"); }
    static LosslessJson object(LosslessJson placement,String field) { objectField(field);String token=placement.token(field);return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] edit(byte[] original,Context context,String object,java.util.function.Function<LosslessJson,byte[]> operation) { return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.objectEdit(object,operation,false)); }
    public static byte[] enable(String path,byte[] original,Context context,String object) { authorize(path,context,object,null);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.enableObject(object)); }
    public static byte[] disable(String path,byte[] original,Context context,String object) { authorize(path,context,object,null);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.nullObject(object)); }
    public static byte[] enableScalar(String path,byte[] original,Context context,String object,String field) { authorize(path,context,object,field);scalar(object,field);return edit(original,context,object,s -> s.enable(field,defaultToken(object,field))); }
    public static byte[] setScalar(String path,byte[] original,Context context,String object,String field,String input) {
        authorize(path,context,object,field);scalar(object,field);String key=object+"."+field;double value;
        try { value=DATA.getProperty(key+".type").equals("int") ? new BigDecimal(input).intValueExact() : Double.parseDouble(input); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a valid "+DATA.getProperty(key+".type")+" deformation value",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(key+".minimum")) || value>Double.parseDouble(DATA.getProperty(key+".maximum"))) throw new IllegalArgumentException("Value violates pinned deformation bounds");
        return edit(original,context,object,s -> s.number(field,value));
    }
    static List<String> exclusions(LosslessJson settings) { String token=settings.token("exclude");return token==null || token.equals("null") ? null : settings.stringArrayPath("exclude"); }
    public static byte[] enableExclusions(String path,byte[] original,Context context) { authorize(path,context,"stiltSettings","exclude");return edit(original,context,"stiltSettings",s -> s.enableArray("exclude")); }
    public static byte[] disableExclusions(String path,byte[] original,Context context) { authorize(path,context,"stiltSettings","exclude");return edit(original,context,"stiltSettings",s -> s.nullableStringArray("exclude",true)); }
    public static boolean materialSyntax(String value) { return value!=null && value.trim().toLowerCase(Locale.ROOT).matches("(?:minecraft:)?[a-z0-9_]+"); }
    private static void material(String value) { if (!materialSyntax(value)) throw new IllegalArgumentException("Enter a Minecraft material key without block states; registry existence is unchecked"); }
    public static byte[] setExclusion(String path,byte[] original,Context context,int index,String value) { authorize(path,context,"stiltSettings","exclude");material(value);return edit(original,context,"stiltSettings",s -> { var values=s.stringArrayPath("exclude");if (index<0 || index>=values.size()) throw new IllegalArgumentException("Select an exclusion");return s.stringArrayPath("exclude",index,value); }); }
    public static byte[] changeExclusion(String path,byte[] original,Context context,RiverProfiles.Action action,int index,String value) {
        authorize(path,context,"stiltSettings","exclude");if (action==null) throw new IllegalArgumentException("Select an exclusion action");if (action==RiverProfiles.Action.ADD) material(value);
        return edit(original,context,"stiltSettings",s -> {
            var values=s.stringArrayPath("exclude");if (action==RiverProfiles.Action.ADD) return s.stringArrayPath("exclude",values.size(),value);
            if (index<0 || index>=values.size()) throw new IllegalArgumentException("Select an exclusion");
            return switch(action) {
                case DUPLICATE -> s.stringArrayDuplicate("exclude",index);
                case REMOVE -> { if (values.size()<=Integer.parseInt(DATA.getProperty("stiltSettings.exclude.minimum"))) throw new IllegalArgumentException("Keep one populated exclusion, or explicitly disable exclusions");yield s.stringArrayPath("exclude",index,null); }
                case UP -> s.stringArrayMove("exclude",index,index-1);
                case DOWN -> s.stringArrayMove("exclude",index,index+1);
                default -> throw new IllegalArgumentException("Unsupported exclusion action");
            };
        });
    }
    static List<String> readOnlyPaths(LosslessJson placement,Context context) {
        var paths=new ArrayList<String>();
        for (String object : OBJECT_FIELDS) try { var settings=object(placement,object);if (settings!=null) for (String field : settings.fields()) { if (object.equals("stiltSettings") && FloatingObjectPalettes.STILT_FIELDS.contains(field)) continue;if (!(object.equals("stiltSettings") ? STILT_FIELDS : VACUUM_FIELDS).contains(field) || !editable(context.list(),object,field)) paths.add(object+"."+field); } }
        catch (IllegalArgumentException unsupported) { paths.add(object); }
        return List.copyOf(paths);
    }
}
