package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Audited UI policy: these dimension fields affect generation; all other fields remain read-only. */
public final class DimensionFields {
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = DimensionFields.class.getResourceAsStream("/dimension-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned field definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static final List<String> EDITABLE = List.of("fluidHeight", "dimensionAngleDeg", "landChance", "objectSurfaceSupportBuffer",
            "bedrock", "decorate", "carvingEnabled", "postProcessing", "postProcessingSlabs",
            "postProcessingWalls", "requireObjectSurfaceSupport", "importedStructures.undergroundYShift",
            "importedStructures.datapackOverrides", "importedFeatures.enabled");
    public static boolean dimension(String path) { return path.startsWith("dimensions/") && path.substring(11).indexOf('/') < 0 && path.endsWith(".json"); }
    private static void authorize(String path, String field) {
        if (!dimension(path) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
    }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
        String value = DEFINITIONS.getProperty(field + ".default");
        return booleanField(field) ? value : new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
    }
    public static byte[] enable(String path, byte[] original, String field) {
        authorize(path, field);
        return new LosslessJson(original).enablePath(field, defaultToken(field));
    }
    public static byte[] editBoolean(String path, byte[] original, String field, boolean value) {
        authorize(path, field);
        if (!booleanField(field)) throw new IllegalArgumentException("Numeric field requires a number");
        return new LosslessJson(original).boolPath(field, value);
    }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static double min(String field) { return Double.parseDouble(DEFINITIONS.getProperty(field + ".minimum")); }
    public static double max(String field) { return Double.parseDouble(DEFINITIONS.getProperty(field + ".maximum")); }
    public static byte[] edit(String path, byte[] original, String field, double value) {
        authorize(path, field);
        if (booleanField(field)) throw new IllegalArgumentException("Boolean field requires true or false");
        if (!Double.isFinite(value) || value < min(field) || value > max(field)
                || (DEFINITIONS.getProperty(field + ".type").equals("int") && value != Math.rint(value)))
            throw new IllegalArgumentException("Value violates the pinned Iris field constraints");
        return new LosslessJson(original).numberPath(field, value);
    }
}
