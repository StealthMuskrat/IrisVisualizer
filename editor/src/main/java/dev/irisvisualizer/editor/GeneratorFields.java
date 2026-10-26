package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Bounded UI policy for generator resources; no noise sampling or preview generation. */
public final class GeneratorFields {
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = GeneratorFields.class.getResourceAsStream("/generator-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned generator definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static final List<String> EDITABLE = List.of("zoom", "opacity", "surfaceDetail", "multiplicitive", "cellFractureZoom", "cellFractureShuffle", "cellFractureHeight", "cellPercentSize", "offsetX", "offsetZ", "seed", "cliffHeightMin", "cliffHeightMax", "interpolator.horizontalScale");
    public static boolean generator(String path) { return "generators".equals(ResourceTypes.base(path)); }
    private static void authorize(String path, String field) {
        if (!generator(path) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Generator field is read-only");
    }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String constraints(String field) {
        if ("long".equals(DEFINITIONS.getProperty(field + ".type"))) return "Signed 64-bit integer (exact)";
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        return "Finite number" + (min == null ? "" : ", minimum " + min) + (max == null ? "" : ", maximum " + max);
    }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
        String token = DEFINITIONS.getProperty(field + ".default");
        return booleanField(field) ? token : new java.math.BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static byte[] enable(String path, byte[] original, String field) { authorize(path, field); return new LosslessJson(original).enablePath(field, defaultToken(field)); }
    public static byte[] editText(String path, byte[] original, String field, String input) {
        authorize(path, field);
        if (booleanField(field)) throw new IllegalArgumentException("Boolean field requires its toggle");
        if ("long".equals(DEFINITIONS.getProperty(field + ".type"))) return new LosslessJson(original).integerPath(field, Long.parseLong(input));
        double value = Double.parseDouble(input);
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        if (!Double.isFinite(value) || (min != null && value < Double.parseDouble(min)) || (max != null && value > Double.parseDouble(max)))
            throw new IllegalArgumentException("Value violates pinned Iris generator constraints");
        return new LosslessJson(original).numberPath(field, value);
    }
    public static byte[] editBoolean(String path, byte[] original, String field, boolean value) {
        authorize(path, field); if (!booleanField(field)) throw new IllegalArgumentException("Numeric field requires a number");
        return new LosslessJson(original).boolPath(field, value);
    }
}
