package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Explicit, exact-file child links. No recursive loading, cycle rejection or generation. */
public final class BiomeChildren {
    public static final List<String> EDITABLE = List.of("children");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = BiomeChildren.class.getResourceAsStream("/biome-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned biome definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public enum Action { DUPLICATE, UP, DOWN }
    public static String description() { return DEFINITIONS.getProperty("children.description",""); }
    public static boolean required() { return "true".equals(DEFINITIONS.getProperty("children.required")); }
    private static void authorize(String path) {
        if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Child biome list is read-only");
    }
    public static List<String> choices(PackWorkspace workspace) { return ResourceTypes.referenceKeys(workspace,"biomes"); }
    public static byte[] enable(String path, byte[] original) {
        authorize(path);
        String value = DEFINITIONS.getProperty("children.default");
        if (!"[]".equals(value)) throw new IllegalStateException("Unsupported child biome default");
        return new LosslessJson(original).enable("children",value);
    }
    public static byte[] edit(String path, byte[] original, int index, String key, PackWorkspace workspace) {
        authorize(path);
        var document = new LosslessJson(original);
        int count = document.stringArrayPath("children").size();
        if (key != null && !choices(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact biome key present in this pack; aliases and target contents are unchecked");
        if (key == null && required() && count <= Integer.parseInt(DEFINITIONS.getProperty("children.minimumEntries")))
            throw new IllegalArgumentException("Keep the required child biome entries");
        return document.stringArrayPath("children",index,key);
    }
    public static byte[] change(String path, byte[] original, int index, Action action) {
        authorize(path);
        var document = new LosslessJson(original);
        return switch (action) {
            case DUPLICATE -> document.stringArrayDuplicate("children",index);
            case UP -> document.stringArrayMove("children",index,index-1);
            case DOWN -> document.stringArrayMove("children",index,index+1);
        };
    }
}
