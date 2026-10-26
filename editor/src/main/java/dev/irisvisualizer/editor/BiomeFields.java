package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Audited biome policy. Unknown fields, palette data and references remain read-only. */
public final class BiomeFields {
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = BiomeFields.class.getResourceAsStream("/biome-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned biome definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static final List<String> EDITABLE = List.of("rarity", "caveMinDepthBelowSurface", "oreDepositFrequencyMultiplier", "oreDepositSizeMultiplier", "lockLayers", "mergeFloatingChildBiomes");
    public static boolean biome(String path) { return "biomes".equals(ResourceTypes.base(path)); }
    private static void authorize(String path, String field) {
        if (!biome(path) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Biome field is read-only");
    }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static double min(String field) { return Double.parseDouble(DEFINITIONS.getProperty(field + ".minimum")); }
    public static double max(String field) { return Double.parseDouble(DEFINITIONS.getProperty(field + ".maximum")); }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
        String token = DEFINITIONS.getProperty(field + ".default");
        return booleanField(field) ? token : new java.math.BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static byte[] enable(String path, byte[] original, String field) { authorize(path, field); return new LosslessJson(original).enable(field, defaultToken(field)); }
    private static void checkNumber(String field, double value) {
        if (booleanField(field) || !Double.isFinite(value) || value < min(field) || value > max(field)
                || ("int".equals(DEFINITIONS.getProperty(field + ".type")) && value != Math.rint(value)))
            throw new IllegalArgumentException("Value violates pinned Iris biome constraints");
    }
    public static byte[] edit(String path, byte[] original, String field, double value) {
        authorize(path, field); checkNumber(field, value); return new LosslessJson(original).number(field, value);
    }
    public static byte[] editBoolean(String path, byte[] original, String field, boolean value) {
        authorize(path, field); if (!booleanField(field)) throw new IllegalArgumentException("Numeric field requires a number");
        return new LosslessJson(original).bool(field, value);
    }
    public static byte[] generatorRange(String path, byte[] original, int index, double minimum, double maximum) {
        if (!biome(path)) throw new IllegalArgumentException("Resource is read-only");
        checkNumber("generators.min", minimum); checkNumber("generators.max", maximum);
        if (minimum > maximum) throw new IllegalArgumentException("Generator minimum must not exceed maximum");
        byte[] next = new LosslessJson(original).objectArrayNumber("generators", index, "min", minimum);
        return new LosslessJson(next).objectArrayNumber("generators", index, "max", maximum);
    }
}
