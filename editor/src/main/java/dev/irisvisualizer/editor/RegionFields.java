package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Region editing uses exact in-pack biome paths, without emulating loader aliases. */
public final class RegionFields {
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = RegionFields.class.getResourceAsStream("/region-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned region definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static final List<String> EDITABLE = List.of("rarity", "shoreHeightZoom", "shoreMinimumWidth", "landBiomeZoom", "shoreBiomeZoom", "seaBiomeZoom", "caveBiomeZoom", "landBiomes", "seaBiomes", "shoreBiomes", "caveBiomes");
    public static boolean region(String path) { return "regions".equals(ResourceTypes.base(path)); }
    private static void authorize(String path, String field) {
        if (!region(path) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Region field is read-only");
    }
    public static boolean arrayField(String field) { return "biomeArray".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String constraints(String field) {
        return "Minimum " + DEFINITIONS.getProperty(field + ".minimum")
                + (DEFINITIONS.containsKey(field + ".maximum") ? ", maximum " + DEFINITIONS.getProperty(field + ".maximum") : "");
    }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
        String token = DEFINITIONS.getProperty(field + ".default");
        return arrayField(field) ? token : new java.math.BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static byte[] enable(String path, byte[] original, String field) { authorize(path, field); return new LosslessJson(original).enable(field, defaultToken(field)); }
    public static byte[] edit(String path, byte[] original, String field, double value) {
        authorize(path, field); if (arrayField(field)) throw new IllegalArgumentException("Use biome list controls");
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        if (!Double.isFinite(value) || (min != null && value < Double.parseDouble(min)) || (max != null && value > Double.parseDouble(max))
                || ("int".equals(DEFINITIONS.getProperty(field + ".type")) && value != Math.rint(value)))
            throw new IllegalArgumentException("Value violates pinned Iris region constraints");
        return new LosslessJson(original).number(field, value);
    }
    public static List<String> biomeKeys(PackWorkspace workspace) {
        return workspace.paths().stream().filter(path -> path.startsWith("biomes/") && path.endsWith(".json"))
                .map(path -> path.substring(7, path.length()-5)).sorted().toList();
    }
    public static byte[] editArray(String path, byte[] original, String field, int index, String value, PackWorkspace workspace) {
        authorize(path, field); if (!arrayField(field)) throw new IllegalArgumentException("Field is not a biome list");
        var document = new LosslessJson(original);
        if (value != null && !biomeKeys(workspace).contains(value)) throw new IllegalArgumentException("Choose an exact biome key present in this pack; loader aliases are not resolved");
        if (value == null && "true".equals(DEFINITIONS.getProperty(field + ".required")) && document.stringArrayPath(field).size() == 1)
            throw new IllegalArgumentException("Keep at least one biome in this required list");
        return document.stringArrayPath(field, index, value);
    }
}
