package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Placement guards in the configurable floating path, plus coordinate warp in all four paths. */
public final class FloatingPlacementConstraints {
    public static final List<String> EDITABLE = List.of("clamp", "slopeCondition", "rotateTowardsSlope", "carvingSupport", "surfaceSupportBuffer", "surfaceSupportDepth", "requireSurfaceSupport", "warp");
    public static final List<String> OBJECT_FIELDS = List.of("clamp", "slopeCondition");
    public static final List<String> FLAGS = List.of("rotateTowardsSlope", "requireSurfaceSupport");
    public static final List<String> SCALARS = List.of("surfaceSupportBuffer", "surfaceSupportDepth");
    public static final List<String> CLAMP_FIELDS = List.of("minimumHeight", "maximumHeight");
    public static final List<String> SLOPE_FIELDS = List.of("minimumSlope", "maximumSlope");
    public static final List<String> WARP_FIELDS = List.of("multiplier");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingPlacementConstraints.class.getResourceAsStream("/floating-placement-constraints.properties")) {
            if (input==null) throw new IOException("Missing pinned placement constraints");DATA.load(input);
            if (!"floatingObjectsOnly".equals(DATA.getProperty("guards.context")) || !"allFourCollections".equals(DATA.getProperty("warp.context"))) throw new IOException("Unsupported placement guard policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int entry,String list,int placement) {
        public Context { if (!FloatingObjectPlacements.validContext(entry,list,placement)) throw new IllegalArgumentException("Select a biome object placement"); }
        public String prefix(String field) { field(field);return FloatingObjectPlacements.placementPrefix(entry,list,placement)+field+"."; }
        public NoiseStyleFields.Target warpTarget() { return NoiseStyleFields.Target.placementWarp(entry,list,placement); }
    }
    private static void field(String value) { if (value==null || !EDITABLE.contains(value)) throw new IllegalArgumentException("Unsupported placement constraint"); }
    static boolean editable(String list,String field) { return EDITABLE.contains(field) && (field.equals("warp") || FloatingObjectPlacements.configured(list)); }
    private static void authorize(String path,Context context,String field) { field(field);if (!BiomeFields.biome(path) || !editable(context.list(),field)) throw new IllegalArgumentException("Placement guards stay read-only in anchored collections"); }
    private static void objectField(String value) { if (value==null || !OBJECT_FIELDS.contains(value)) throw new IllegalArgumentException("Unsupported placement bound object"); }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) { field(field);return DATA.getProperty(field+".default"); }
    public static List<String> carvingModes() { return List.of(DATA.getProperty("carvingSupport.choices").split(",")); }
    public static String carvingHelp(String value) { if (value==null || !carvingModes().contains(value)) throw new IllegalArgumentException("Unknown carving mode");return DATA.getProperty("carvingSupport."+value+".description",""); }
    private static String key(String field,String member) { objectField(field);if (!(field.equals("clamp") ? CLAMP_FIELDS : SLOPE_FIELDS).contains(member)) throw new IllegalArgumentException("Unsupported bound member");return field+"."+member; }
    public static String boundDefault(String field,String member) { return DATA.getProperty(key(field,member)+".default"); }
    public static String constraints(String field) { if (!SCALARS.contains(field)) throw new IllegalArgumentException("Unsupported placement scalar");return "Exact integer, minimum "+DATA.getProperty(field+".minimum")+", maximum "+DATA.getProperty(field+".maximum"); }
    public static String boundConstraints(String field,String member) { String key=key(field,member);return (field.equals("clamp") ? "Exact integer" : "Finite number")+", minimum "+DATA.getProperty(key+".minimum")+", maximum "+DATA.getProperty(key+".maximum"); }
    public static String warpDefault() { return DATA.getProperty("warp.default"); }
    public static String multiplierDefault() { return DATA.getProperty("warp.multiplier.default"); }
    public static String multiplierConstraints() { return "Finite number, minimum "+DATA.getProperty("warp.multiplier.minimum")+". Iris uses half this multiplier for horizontal warp offsets; geometry limits are not validated here."; }
    static LosslessJson object(LosslessJson placement,String field) { objectField(field);String token=placement.token(field);return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static byte[] editPlacement(byte[] original,Context context,java.util.function.Function<LosslessJson,byte[]> action) { return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),action); }
    public static byte[] create(String path,byte[] original,Context context,String field) {
        authorize(path,context,field);objectField(field);return editPlacement(original,context,p -> { if (p.token(field)!=null) throw new IllegalArgumentException("Bound object already exists or has an unsupported type");return p.enableObject(field); });
    }
    public static byte[] enableFlag(String path,byte[] original,Context context,String field) { authorize(path,context,field);if (!FLAGS.contains(field)) throw new IllegalArgumentException("Unsupported flag");return editPlacement(original,context,p -> p.enable(field,defaultToken(field))); }
    public static byte[] setFlag(String path,byte[] original,Context context,String field,boolean value) { authorize(path,context,field);if (!FLAGS.contains(field)) throw new IllegalArgumentException("Unsupported flag");return editPlacement(original,context,p -> p.bool(field,value)); }
    private static double number(String input,String key,boolean integer) {
        double value;try { value=integer ? new BigDecimal(input).intValueExact() : Double.parseDouble(input); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException(integer ? "Enter an exact signed 32-bit integer" : "Enter a finite number",invalid); }
        String maximum=DATA.getProperty(key+".maximum");if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(key+".minimum")) || maximum!=null && value>Double.parseDouble(maximum)) throw new IllegalArgumentException("Value violates pinned placement constraints");return value;
    }
    private static byte[] bound(LosslessJson object,String member,double value) { return object.token(member)==null ? object.enable(member,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : object.number(member,value); }
    public static byte[] setBounds(String path,byte[] original,Context context,String field,String minimum,String maximum) {
        authorize(path,context,field);objectField(field);var members=field.equals("clamp") ? CLAMP_FIELDS : SLOPE_FIELDS;
        double min=number(minimum,key(field,members.getFirst()),field.equals("clamp")),max=number(maximum,key(field,members.getLast()),field.equals("clamp"));
        if (min>max) throw new IllegalArgumentException("Enter ordered minimum and maximum bounds");
        return editPlacement(original,context,p -> p.objectEdit(field,o -> bound(new LosslessJson(bound(o,members.getFirst(),min)),members.getLast(),max),false));
    }
    public static byte[] enableScalar(String path,byte[] original,Context context,String field) { authorize(path,context,field);if (!SCALARS.contains(field)) throw new IllegalArgumentException("Unsupported scalar");return editPlacement(original,context,p -> p.enable(field,defaultToken(field))); }
    public static byte[] setScalar(String path,byte[] original,Context context,String field,String input) { authorize(path,context,field);if (!SCALARS.contains(field)) throw new IllegalArgumentException("Unsupported scalar");double value=number(input,field,true);return editPlacement(original,context,p -> p.number(field,value)); }
    public static byte[] setCarving(String path,byte[] original,Context context,String value) {
        authorize(path,context,"carvingSupport");if (value==null || !carvingModes().contains(value)) throw new IllegalArgumentException("Choose a pinned carving mode");
        return editPlacement(original,context,p -> p.token("carvingSupport")==null ? p.enableStringPath("carvingSupport",value) : p.stringValue("carvingSupport",value));
    }
    public static byte[] enableMultiplier(String path,byte[] original,Context context) { authorize(path,context,"warp");return editPlacement(original,context,p -> p.objectEdit("warp",w -> w.enable("multiplier",multiplierDefault()),false)); }
    public static byte[] setMultiplier(String path,byte[] original,Context context,String input) { authorize(path,context,"warp");double value=number(input,"warp.multiplier",false);return editPlacement(original,context,p -> p.objectEdit("warp",w -> w.number("multiplier",value),false)); }
    static Context warpContext(String path) {
        var parsed=FloatingObjectPlacements.parsePath(path);
        return parsed!=null && parsed.suffix().equals("warp") ? new Context(parsed.entry(),parsed.list(),parsed.placement()) : null;
    }
    static List<String> readOnlyPaths(LosslessJson placement,Context context) {
        var paths=new ArrayList<String>();
        for (String field : OBJECT_FIELDS) if (editable(context.list(),field)) try {
            var object=object(placement,field);if (object!=null) for (String member : object.fields()) if (!(field.equals("clamp") ? CLAMP_FIELDS : SLOPE_FIELDS).contains(member)) paths.add(field+"."+member);
        } catch (IllegalArgumentException unsupported) { paths.add(field); }
        String token=placement.token("warp");if (token!=null) try {
            var warp=new LosslessJson(token.getBytes(StandardCharsets.UTF_8));for (String member : warp.fields()) if (!NoiseStyleFields.EDITABLE.contains(member) && !WARP_FIELDS.contains(member)) paths.add("warp."+member);
        } catch (IllegalArgumentException unsupported) { paths.add("warp"); }
        return List.copyOf(paths);
    }
}
