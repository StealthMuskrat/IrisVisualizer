package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Bounded meander and surface-shape settings; channel fallback values and geometry are not evaluated. */
public final class RiverGeometryFields {
    public static final List<String> OBJECT_FIELDS = List.of("geometry");
    public static final List<String> GEOMETRY_FIELDS = List.of("meanders", "surface");
    public static final List<String> MEANDER_FIELDS = List.of("primaryWavelength", "detailWavelength", "primaryStrength", "detailStrength", "maximumOffsetRatio", "smoothingPasses", "maximumTurnDegrees");
    public static final List<String> SURFACE_FIELDS = List.of("bedRoundness", "bedRoughness", "wallRoughness", "roughnessWavelength", "radialBase", "radialMinimum", "radialMaximum", "primaryLobeStrength", "detailLobeStrength", "ceilingRoughness");
    private static final Properties DEFINITIONS=new Properties();
    static {
        try (var input=RiverGeometryFields.class.getResourceAsStream("/river-geometry-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned river geometry definitions");
            DEFINITIONS.load(input);
            for (String object : List.of("geometry","meanders","surface")) if (!"{}".equals(DEFINITIONS.getProperty(object+".default"))) throw new IOException("Unsupported geometry object defaults");
            for (String section : GEOMETRY_FIELDS) for (String field : fields(section)) {
                String kind=DEFINITIONS.getProperty(section+"."+field+".type", "");
                if (!List.of("int","double","Integer","Double").contains(kind) || nullable(section,field) && !"null".equals(defaultToken(section,field))) throw new IOException("Unsupported geometry scalar defaults/types");
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("River geometry is dimension-only"); }
    public static List<String> fields(String section) {
        return switch(section) { case "meanders" -> MEANDER_FIELDS; case "surface" -> SURFACE_FIELDS; default -> throw new IllegalArgumentException("Unsupported geometry section"); };
    }
    private static void authorize(String path, String section, String field) {
        authorize(path); if (!fields(section).contains(field)) throw new IllegalArgumentException("Geometry field is read-only");
    }
    public static String description(String object) { return DEFINITIONS.getProperty(object+".description", ""); }
    public static String help(String section, String field) { return DEFINITIONS.getProperty(section+"."+field+".description", ""); }
    public static String readOnlyReason(String section, String field) { return DEFINITIONS.getProperty(section+"."+field+".readOnlyReason", ""); }
    public static boolean nullable(String section, String field) {
        String kind=DEFINITIONS.getProperty(section+"."+field+".type"); return "Double".equals(kind) || "Integer".equals(kind);
    }
    public static boolean integer(String section, String field) {
        String kind=DEFINITIONS.getProperty(section+"."+field+".type"); return "int".equals(kind) || "Integer".equals(kind);
    }
    public static String defaultToken(String section, String field) {
        if (!fields(section).contains(field)) throw new IllegalArgumentException("Geometry field is read-only");
        String token=DEFINITIONS.getProperty(section+"."+field+".default"); return nullable(section,field) ? token : new BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String constraints(String section, String field) {
        String prefix=section+"."+field, min=DEFINITIONS.getProperty(prefix+".minimum"), max=DEFINITIONS.getProperty(prefix+".maximum");
        return (integer(section,field) ? "Signed 32-bit integer" : "Finite number")+(min==null ? "" : ", minimum "+min)+(max==null ? "" : ", maximum "+max);
    }
    public static String draftPrefix(String section) { fields(section); return "hydrology#rivers.geometry."+section+"."; }
    private static byte[] createAbsent(LosslessJson object, String field) {
        if (object.token(field)!=null) throw new IllegalArgumentException("Geometry object is already present or unsupported");
        return object.enableObject(field);
    }
    public static byte[] enableGeometry(String path, byte[] original) { authorize(path); return HydrologyFields.editRivers(original,rivers -> createAbsent(rivers,"geometry")); }
    private static byte[] editGeometry(byte[] original, Function<LosslessJson,byte[]> operation) { return HydrologyFields.editRivers(original,rivers -> rivers.objectEdit("geometry",operation,false)); }
    private static byte[] editSection(byte[] original, String section, Function<LosslessJson,byte[]> operation) { return editGeometry(original,geometry -> geometry.objectEdit(section,operation,false)); }
    public static byte[] enableSection(String path, byte[] original, String section) {
        authorize(path); fields(section); return editGeometry(original,geometry -> createAbsent(geometry,section));
    }
    public static byte[] enableScalar(String path, byte[] original, String section, String field) {
        authorize(path,section,field);
        if (nullable(section,field)) throw new IllegalArgumentException("Supply an explicit override value or keep the channel default");
        return editSection(original,section,object -> object.enable(field,defaultToken(section,field)));
    }
    public static byte[] setText(String path, byte[] original, String section, String field, String input) {
        authorize(path,section,field);
        if (input==null) throw new IllegalArgumentException("Enter a numeric geometry value");
        double value;
        if (integer(section,field)) {
            try { value=new BigDecimal(input).intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        } else value=Double.parseDouble(input);
        String prefix=section+"."+field, min=DEFINITIONS.getProperty(prefix+".minimum"), max=DEFINITIONS.getProperty(prefix+".maximum");
        if (!Double.isFinite(value) || min!=null && value<Double.parseDouble(min) || max!=null && value>Double.parseDouble(max)) throw new IllegalArgumentException("Value violates pinned geometry constraints");
        return editSection(original,section,object -> nullable(section,field) ? object.nullableNumber(field,value) : object.number(field,value));
    }
    public static byte[] reset(String path, byte[] original, String section, String field) {
        authorize(path,section,field);
        if (!nullable(section,field)) throw new IllegalArgumentException("This primitive geometry field has a numeric default");
        return editSection(original,section,object -> object.nullableNumber(field,null));
    }
    private static LosslessJson child(LosslessJson parent, String field) {
        String token=parent.token(field); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    static LosslessJson geometry(LosslessJson rivers) { return child(rivers,"geometry"); }
    static LosslessJson section(LosslessJson geometry, String section) { fields(section); return child(geometry,section); }
    static List<String> readOnlyPaths(LosslessJson rivers) {
        var paths=new ArrayList<String>();
        try {
            var geometry=geometry(rivers); if (geometry==null) return List.of();
            for (String field : geometry.fields()) {
                if (!GEOMETRY_FIELDS.contains(field)) { paths.add("geometry."+field); continue; }
                try {
                    var object=section(geometry,field);
                    for (String member : object.fields()) if (!fields(field).contains(member)) paths.add("geometry."+field+"."+member);
                } catch (IllegalArgumentException mismatch) { paths.add("geometry."+field); }
            }
        } catch (IllegalArgumentException mismatch) { paths.add("geometry"); }
        return List.copyOf(paths);
    }
}
