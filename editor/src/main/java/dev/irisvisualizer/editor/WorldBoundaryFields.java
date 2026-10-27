package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Native border configuration only; no live world is contacted. */
public final class WorldBoundaryFields {
    public static final List<String> OBJECT_FIELDS = List.of("worldBoundary");
    public static final List<String> EDITABLE = List.of("center", "size", "warningDistance", "damageBuffer", "damageAmount");
    public static final List<String> AXES = List.of("x", "z");
    public static final List<String> SCALARS = List.of("size", "warningDistance", "damageBuffer", "damageAmount");
    private static final Properties DATA=new Properties();
    static {
        try (var input=WorldBoundaryFields.class.getResourceAsStream("/world-boundary-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned world-boundary definitions");DATA.load(input);
            if (!"leaveNativeBorderUnchanged".equals(DATA.getProperty("null.policy")) || !"fullDiameter".equals(DATA.getProperty("size.policy"))) throw new IOException("Unsupported border policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("World borders are dimension-only"); }
    public static String prefix() { return "worldBoundary#"; }
    public static String help(String field) { return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) {
        if (!SCALARS.contains(field) && !List.of("center.x","center.z").contains(field)) throw new IllegalArgumentException("Unsupported border field");
        return DATA.getProperty(field+".default");
    }
    public static String constraints(String field) {
        if (!SCALARS.contains(field) && !List.of("center.x","center.z").contains(field)) throw new IllegalArgumentException("Unsupported border field");
        return (field.equals("warningDistance") ? "Exact integer" : "Finite number")+"; minimum "+DATA.getProperty(field+".minimum")+(DATA.getProperty(field+".maximum")==null ? "." : ", maximum "+DATA.getProperty(field+".maximum")+".");
    }
    static LosslessJson object(LosslessJson document) {
        String token=document.token("worldBoundary");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    static LosslessJson center(LosslessJson boundary) {
        String token=boundary.token("center");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    static boolean supported(LosslessJson object,String field) {
        String token=object.token(field);if (token==null) return true;
        if (!LosslessJson.numericToken(token)) return false;
        if (field.equals("warningDistance")) {
            try { new BigDecimal(token).intValueExact(); } catch (NumberFormatException | ArithmeticException invalid) { return false; }
        }
        return true;
    }
    private static double finite(String field,String input) {
        try {
            double value=new BigDecimal(input).doubleValue();
            if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(field+".minimum")) || DATA.getProperty(field+".maximum")!=null && value>Double.parseDouble(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Border value violates native bounds");
            return value;
        } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite border number",invalid); }
    }
    private static int warning(String input) {
        try { int value=new BigDecimal(input).intValueExact();if (value<0) throw new IllegalArgumentException("Warning distance must be nonnegative");return value; }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact nonnegative signed 32-bit warning distance",invalid); }
    }
    private static byte[] write(LosslessJson object,String field,double value) {
        if (!supported(object,field)) throw new IllegalArgumentException("Stored border field has an unsupported type");
        return object.token(field)==null ? object.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : object.number(field,value);
    }
    public static byte[] enable(String path,byte[] original) { authorize(path);return new LosslessJson(original).enableObject("worldBoundary"); }
    /** Explicitly clears the whole inline configuration; omission/null never resets a live border. */
    public static byte[] disable(String path,byte[] original) { authorize(path);return new LosslessJson(original).nullObject("worldBoundary"); }
    public static byte[] enableScalar(String path,byte[] original,String field) {
        authorize(path);if (!SCALARS.contains(field)) throw new IllegalArgumentException("Unsupported border scalar");
        return new LosslessJson(original).objectEdit("worldBoundary",b -> b.enable(field,defaultToken(field)),false);
    }
    public static byte[] setScalar(String path,byte[] original,String field,String input) {
        authorize(path);if (!SCALARS.contains(field)) throw new IllegalArgumentException("Unsupported border scalar");
        int exact=field.equals("warningDistance") ? warning(input) : 0;double value=field.equals("warningDistance") ? exact : finite(field,input);
        return new LosslessJson(original).objectEdit("worldBoundary",b -> {
            if (b.token(field)==null || !supported(b,field)) throw new IllegalArgumentException("Enable this numeric border field first; unsupported values remain read-only");
            return field.equals("warningDistance") ? b.integer(field,exact) : b.number(field,value);
        },false);
    }
    public static byte[] setCenter(String path,byte[] original,String x,String z) {
        authorize(path);double nextX=finite("center.x",x),nextZ=finite("center.z",z);
        return new LosslessJson(original).objectEdit("worldBoundary",b -> b.objectEdit("center",c -> {
            if (!supported(c,"x") || !supported(c,"z")) throw new IllegalArgumentException("Stored center coordinates have unsupported types");
            return write(new LosslessJson(write(c,"x",nextX)),"z",nextZ);
        },true),false);
    }
    static List<String> readOnlyPaths(LosslessJson document) {
        var paths=new ArrayList<String>();
        try {
            var boundary=object(document);if (boundary==null) return List.of();
            for (String field : boundary.fields()) {
                if (field.equals("center")) {
                    try { var center=center(boundary);for (String axis : center.fields()) if (!AXES.contains(axis) || !supported(center,axis)) paths.add("worldBoundary.center."+axis); }
                    catch (IllegalArgumentException invalid) { paths.add("worldBoundary.center"); }
                } else if (!SCALARS.contains(field) || !supported(boundary,field)) paths.add("worldBoundary."+field);
            }
        } catch (IllegalArgumentException invalid) { paths.add("worldBoundary"); }
        return List.copyOf(paths);
    }
}
