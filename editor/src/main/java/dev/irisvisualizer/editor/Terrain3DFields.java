package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Bounded profile editing only; no terrain, density or fissure generation is initialized. */
public final class Terrain3DFields {
    public static final List<String> OBJECT_FIELDS = List.of("terrain3D");
    public static final List<String> EDITABLE = List.of("enabled","seed","amplitude","horizontalScale","verticalScale","crackDepth","crackWidth","crackScale","minimumSlope","slopeFade","fluidClearance","fluidFade");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = Terrain3DFields.class.getResourceAsStream("/terrain3d-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned terrain definitions");
            DEFINITIONS.load(input);
            if (!"null".equals(DEFINITIONS.getProperty("profileDefault"))) throw new IOException("Unsupported terrain profile default");
            if (!"nativeTerrainRanges".equals(DEFINITIONS.getProperty("style.runtimeRule"))) throw new IOException("Missing pinned terrain style bounds");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) {
        if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Terrain profile is read-only");
    }
    private static void authorize(String path, String field) {
        authorize(path); if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Terrain field is read-only");
    }
    public static String description() { return DEFINITIONS.getProperty("description",""); }
    public static String description(String field) { return DEFINITIONS.getProperty(field+".description",""); }
    public static String styleDefault(String field) {
        if (!List.of("densityStyle","crackStyle").contains(field)) throw new IllegalArgumentException("Unsupported terrain style");
        return DEFINITIONS.getProperty(field+".default");
    }
    static String styleBound(String field, String bound) { return DEFINITIONS.getProperty("style."+field+"."+bound); }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field+".type")); }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Terrain field is read-only");
        String token = DEFINITIONS.getProperty(field+".default");
        return booleanField(field) ? token : new java.math.BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String constraints(String field) {
        if ("long".equals(DEFINITIONS.getProperty(field+".type"))) return "Signed 64-bit integer (exact)";
        return "Finite number, minimum "+DEFINITIONS.getProperty(field+".minimum")+", maximum "+DEFINITIONS.getProperty(field+".maximum");
    }
    public static byte[] enableProfile(String path, byte[] original) {
        authorize(path); return new LosslessJson(original).enableObject("terrain3D");
    }
    public static byte[] enable(String path, byte[] original, String field) {
        authorize(path,field);
        return new LosslessJson(original).objectEdit("terrain3D",object -> object.enable(field,defaultToken(field)),false);
    }
    public static byte[] editBoolean(String path, byte[] original, String field, boolean value) {
        authorize(path,field);
        if (!booleanField(field)) throw new IllegalArgumentException("Numeric terrain field requires a number");
        return new LosslessJson(original).objectEdit("terrain3D",object -> object.token(field)==null
                ? object.enable(field,Boolean.toString(value)) : object.bool(field,value),false);
    }
    public static byte[] editText(String path, byte[] original, String field, String input) {
        authorize(path,field);
        if (booleanField(field)) throw new IllegalArgumentException("Boolean terrain field requires its toggle");
        if ("long".equals(DEFINITIONS.getProperty(field+".type"))) {
            long value = Long.parseLong(input);
            return new LosslessJson(original).objectEdit("terrain3D",object -> object.integer(field,value),false);
        }
        double value = Double.parseDouble(input);
        if (!Double.isFinite(value) || value < Double.parseDouble(DEFINITIONS.getProperty(field+".minimum"))
                || value > Double.parseDouble(DEFINITIONS.getProperty(field+".maximum")))
            throw new IllegalArgumentException("Value violates pinned native terrain bounds");
        return new LosslessJson(original).objectEdit("terrain3D",object -> object.number(field,value),false);
    }
    static LosslessJson object(LosslessJson document) {
        String token = document.token("terrain3D");
        return token == null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
}
