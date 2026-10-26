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
            "importedStructures.datapackOverrides", "importedFeatures.enabled",
            "importedStructures.disabled", "importedStructures.disabledExact",
            "importedFeatures.disabled", "importedFeatures.disabledExact");
    public static boolean dimension(String path) { return path.startsWith("dimensions/") && path.substring(11).indexOf('/') < 0 && path.endsWith(".json"); }
    private static void authorize(String path, String field) {
        if (!dimension(path) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
    }
    public static boolean arrayField(String field) { return "stringArray".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static byte[] editArray(String path, byte[] original, String field, int index, String value) {
        authorize(path, field);
        if (!arrayField(field)) throw new IllegalArgumentException("Field is not a string array");
        // Syntax only: registries are not available to this editor. Existing strings remain untouched.
        if (value != null) {
            boolean prefixes = "true".equals(DEFINITIONS.getProperty(field + ".prefixes"));
            String pattern = prefixes ? "[a-z0-9_.-]+:[a-z0-9/._-]*" : "[a-z0-9_.-]+:[a-z0-9/._-]+";
            if (!value.matches(pattern)) throw new IllegalArgumentException("Enter a lowercase namespace:key" + (prefixes ? " (namespace: is also allowed)" : ""));
        }
        return new LosslessJson(original).stringArrayPath(field, index, value);
    }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
        String value = DEFINITIONS.getProperty(field + ".default");
        return booleanField(field) || arrayField(field) ? value : new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
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
        if (booleanField(field) || arrayField(field)) throw new IllegalArgumentException("Field requires its typed control");
        if (!Double.isFinite(value) || value < min(field) || value > max(field)
                || (DEFINITIONS.getProperty(field + ".type").equals("int") && value != Math.rint(value)))
            throw new IllegalArgumentException("Value violates the pinned Iris field constraints");
        return new LosslessJson(original).numberPath(field, value);
    }
}
