package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Rotation authoring only where the floating consumer preserves configured transforms. */
public final class FloatingObjectRotations {
    public static final List<String> EDITABLE = List.of("rotation");
    public static final List<String> ROTATION_FIELDS = List.of("enabled", "xAxis", "yAxis", "zAxis");
    public static final List<String> AXIS_FIELDS = List.of("enabled", "min", "max", "interval");
    public static final List<String> AXES = List.of("xAxis", "yAxis", "zAxis");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingObjectRotations.class.getResourceAsStream("/floating-object-rotation.properties")) {
            if (input==null) throw new IOException("Missing pinned floating rotation definitions");DATA.load(input);
            if (!"floatingObjectsOnly".equals(DATA.getProperty("context")) || !"contextualCreationClassInline".equals(DATA.getProperty("axis.policy"))) throw new IOException("Unsupported floating rotation policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int entry,String list,int placement) {
        public Context { if (entry<0 || placement<0 || !"floatingObjects".equals(list)) throw new IllegalArgumentException("Configured rotation is editable only in floatingObjects; anchored consumers replace it"); }
        public String prefix() { return FloatingObjectPlacements.placementPrefix(entry,list,placement)+"rotation."; }
        public String axisPrefix(String axis) { axis(axis);return prefix()+axis+"."; }
    }
    static boolean editable(String list,String field) { return "floatingObjects".equals(list) && EDITABLE.contains(field); }
    private static void authorize(String path) { if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Floating rotation is biome-only"); }
    private static void axis(String value) { if (value==null || !AXES.contains(value)) throw new IllegalArgumentException("Unsupported rotation axis"); }
    private static void axisField(String value) { if (value==null || !AXIS_FIELDS.contains(value)) throw new IllegalArgumentException("Unsupported axis field"); }
    public static String help() { return DATA.getProperty("rotation.description",""); }
    public static String axisHelp(String value) { axis(value);return DATA.getProperty("rotation."+value+".description",""); }
    public static String fieldHelp(String value) { axisField(value);return DATA.getProperty("axis."+value+".description",""); }
    public static String axisDefault(String value) { axisField(value);return DATA.getProperty("axis."+value+".default"); }
    public static String rotationDefault() { return DATA.getProperty("rotation.enabled.default"); }
    public static String creationToken(String value) { axis(value);return DATA.getProperty("rotation."+value+".default"); }
    public static String constraints(String value) { axisField(value);if (value.equals("enabled")) return "Boolean";return "Finite degrees, minimum "+DATA.getProperty("axis."+value+".minimum")+", maximum "+DATA.getProperty("axis."+value+".maximum"); }
    static LosslessJson object(LosslessJson placement) { String token=placement.token("rotation");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static LosslessJson axisObject(LosslessJson rotation,String value) { axis(value);String token=rotation.token(value);return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] editPlacement(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),action); }
    private static byte[] editRotation(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return editPlacement(original,context,p -> p.objectEdit("rotation",action,false)); }
    private static byte[] editAxis(byte[] original,Context context,String axis,Function<LosslessJson,byte[]> action) { axis(axis);return editRotation(original,context,r -> r.objectEdit(axis,action,false)); }
    public static byte[] create(String path,byte[] original,Context context) {
        authorize(path);return editPlacement(original,context,p -> { if (p.token("rotation")!=null) throw new IllegalArgumentException("Rotation already exists or has an unsupported type");return p.enableObject("rotation"); });
    }
    public static byte[] createAxis(String path,byte[] original,Context context,String axis) {
        authorize(path);axis(axis);return editRotation(original,context,r -> { if (r.token(axis)!=null) throw new IllegalArgumentException("Axis already exists or has an unsupported type");return r.objectEdit(axis,ignored -> creationToken(axis).getBytes(StandardCharsets.UTF_8),true); });
    }
    public static byte[] enableRotationFlag(String path,byte[] original,Context context) { authorize(path);return editRotation(original,context,r -> r.enable("enabled",rotationDefault())); }
    public static byte[] setRotationFlag(String path,byte[] original,Context context,boolean enabled) { authorize(path);return editRotation(original,context,r -> r.bool("enabled",enabled)); }
    public static byte[] enableAxisField(String path,byte[] original,Context context,String axis,String member) {
        authorize(path);axis(axis);if (!List.of("enabled","interval").contains(member)) throw new IllegalArgumentException("Use paired angle bounds");return editAxis(original,context,axis,a -> a.enable(member,axisDefault(member)));
    }
    public static byte[] setAxisFlag(String path,byte[] original,Context context,String axis,boolean enabled) { authorize(path);return editAxis(original,context,axis,a -> a.bool("enabled",enabled)); }
    private static double angle(String field,String input) {
        double value;try { value=Double.parseDouble(input); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter finite rotation degrees",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty("axis."+field+".minimum")) || value>Double.parseDouble(DATA.getProperty("axis."+field+".maximum"))) throw new IllegalArgumentException("Rotation violates pinned angle bounds");return value;
    }
    private static byte[] number(LosslessJson object,String member,double value) { return object.token(member)==null ? object.enable(member,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : object.number(member,value); }
    public static byte[] setAngles(String path,byte[] original,Context context,String axis,String minimum,String maximum) {
        authorize(path);axis(axis);double min=angle("min",minimum),max=angle("max",maximum);
        if (min>max) throw new IllegalArgumentException("Use ordered angle bounds; 0/0 means unlimited rotation and equal nonzero bounds lock the angle");
        return editAxis(original,context,axis,a -> number(new LosslessJson(number(a,"min",min)),"max",max));
    }
    public static byte[] setInterval(String path,byte[] original,Context context,String axis,String input) { authorize(path);axis(axis);double value=angle("interval",input);return editAxis(original,context,axis,a -> a.number("interval",value)); }
    static List<String> readOnlyPaths(LosslessJson placement) {
        var paths=new ArrayList<String>();LosslessJson rotation;
        try { rotation=object(placement); } catch (IllegalArgumentException unsupported) { return List.of("rotation"); }
        if (rotation==null) return List.of();
        for (String member : rotation.fields()) if (!ROTATION_FIELDS.contains(member)) paths.add("rotation."+member);
        for (String axis : AXES) try {
            var object=axisObject(rotation,axis);if (object!=null) for (String member : object.fields()) if (!AXIS_FIELDS.contains(member)) paths.add("rotation."+axis+"."+member);
        } catch (IllegalArgumentException unsupported) { paths.add("rotation."+axis); }
        return List.copyOf(paths);
    }
}
