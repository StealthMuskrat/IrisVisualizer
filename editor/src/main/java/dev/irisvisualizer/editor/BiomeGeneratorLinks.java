package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Exact-file assignment and structural editing of biome generator links; loader aliases are not resolved. */
public final class BiomeGeneratorLinks {
    public static final List<String> EDITABLE = List.of("generator");
    public static final List<String> LIST_FIELDS = List.of("generators");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = BiomeGeneratorLinks.class.getResourceAsStream("/biome-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned biome definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public enum Action { DUPLICATE, REMOVE, UP, DOWN }
    public static int minimumEntries() { return Integer.parseInt(DEFINITIONS.getProperty("generators.minimumEntries")); }
    public static String defaultGenerator() { return DEFINITIONS.getProperty("generators.defaultGenerator"); }
    public static String description() { return DEFINITIONS.getProperty("generators.description", ""); }
    private static void authorize(String path) {
        if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Biome generator list is read-only");
    }
    public static List<String> choices(PackWorkspace workspace) {
        return ResourceTypes.referenceKeys(workspace,"generators");
    }
    private static void requireKey(PackWorkspace workspace, String key) {
        if (key == null || !choices(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact generator key present in this pack; aliases and target contents are not checked");
    }
    private static byte[] link(String key, double minimum, double maximum) {
        BiomeFields.validateGeneratorRange(minimum, maximum);
        byte[] created = new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("generator", key);
        created = new LosslessJson(created).enable("min", java.math.BigDecimal.valueOf(minimum).stripTrailingZeros().toPlainString());
        return new LosslessJson(created).enable("max", java.math.BigDecimal.valueOf(maximum).stripTrailingZeros().toPlainString());
    }
    /** Makes the pinned one-link implicit list explicit, without choosing a pack resource silently. */
    public static byte[] enable(String path, byte[] original) {
        authorize(path);
        var document = new LosslessJson(new LosslessJson(original).enable("generators", "[]"));
        return document.objectArrayInsert("generators", 0, link(defaultGenerator(), Double.parseDouble(BiomeFields.generatorDefault("min")), Double.parseDouble(BiomeFields.generatorDefault("max"))));
    }
    public static byte[] add(String path, byte[] original, PackWorkspace workspace, String key, double minimum, double maximum) {
        authorize(path); requireKey(workspace, key);
        var document = new LosslessJson(original);
        int index = document.objectArray("generators").size();
        return document.objectArrayInsert("generators", index, link(key, minimum, maximum));
    }
    public static byte[] assign(String path, byte[] original, int index, PackWorkspace workspace, String key) {
        authorize(path); requireKey(workspace, key);
        return new LosslessJson(original).objectArrayEdit("generators", index, object -> object.token("generator") == null
                ? object.enableStringPath("generator", key) : object.stringValue("generator", key));
    }
    /** User-provided paired values explicitly enable missing members and replace only their tokens. */
    public static byte[] range(String path, byte[] original, int index, double minimum, double maximum) {
        authorize(path); BiomeFields.validateGeneratorRange(minimum, maximum);
        byte[] prepared = new LosslessJson(original).objectArrayEdit("generators", index, object -> {
            byte[] next = object.sourceBytes();
            for (String field : List.of("min", "max")) {
                var current = new LosslessJson(next);
                if (current.token(field) == null) next = current.enable(field, BiomeFields.generatorDefault(field));
            }
            return next;
        });
        return BiomeFields.generatorRange(path, prepared, index, minimum, maximum);
    }
    public static byte[] change(String path, byte[] original, int index, Action action) {
        authorize(path);
        var document = new LosslessJson(original); var entries = document.objectArray("generators");
        if (index < 0 || index >= entries.size()) throw new IllegalArgumentException("Generator link index out of range");
        return switch (action) {
            case DUPLICATE -> document.objectArrayInsert("generators", index+1, entries.get(index).sourceBytes());
            case REMOVE -> {
                if (entries.size() <= minimumEntries()) throw new IllegalArgumentException("Keep at least " + minimumEntries() + " biome generator link");
                yield document.objectArrayRemove("generators", index);
            }
            case UP -> document.objectArrayMove("generators", index, index-1);
            case DOWN -> document.objectArrayMove("generators", index, index+1);
        };
    }
}
