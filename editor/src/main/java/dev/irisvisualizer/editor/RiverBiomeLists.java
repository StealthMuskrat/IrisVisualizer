package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Exact-file river biome selections; null inherits and an empty list clears inheritance. */
public final class RiverBiomeLists {
    public static final List<String> EDITABLE = List.of("surfaceBiomes", "mouthBiomes", "shoreBiomes", "bankBiomes", "floodedCaveBiomes");
    public enum Action { DUPLICATE, UP, DOWN }
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = RiverBiomeLists.class.getResourceAsStream("/river-policy-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned river biome list definitions");
            DEFINITIONS.load(input);
            for (String field : EDITABLE) if (!"null".equals(DEFINITIONS.getProperty(field+".default")) || !"biomeArray".equals(DEFINITIONS.getProperty(field+".type"))
                    || !"biomes".equals(DEFINITIONS.getProperty(field+".referenceFamily")) || !"true".equals(DEFINITIONS.getProperty(field+".emptyClearsInheritance")))
                throw new IOException("Unsupported river biome list contract");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path, String field) {
        String context = ResourceTypes.base(path);
        if (context == null || !CaveProfileFields.CONTEXTS.contains(context) || !EDITABLE.contains(field)) throw new IllegalArgumentException("River biome list is read-only");
    }
    public static String description(String field) { return DEFINITIONS.getProperty(field+".description", ""); }
    public static String draftPrefix(String context, String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported river biome list");
        return RiverPolicyFields.draftPrefix(context)+field+".";
    }
    public static List<String> choices(PackWorkspace workspace) { return ResourceTypes.referenceKeys(workspace,"biomes"); }
    public static byte[] setInherited(String path, byte[] original, String field, boolean inherit) {
        authorize(path,field);
        return new LosslessJson(original).objectEdit("riverPolicy",policy -> policy.nullableStringArray(field,inherit),false);
    }
    public static byte[] edit(String path, byte[] original, String field, int index, String key, PackWorkspace workspace) {
        authorize(path,field);
        if (key != null && !choices(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact biome key present in this pack; aliases and target contents are unchecked");
        return new LosslessJson(original).objectEdit("riverPolicy",policy -> policy.stringArrayPath(field,index,key),false);
    }
    public static byte[] change(String path, byte[] original, String field, int index, Action action) {
        authorize(path,field);
        if (action == null) throw new IllegalArgumentException("Choose a river biome list action");
        return new LosslessJson(original).objectEdit("riverPolicy",policy -> switch (action) {
            case DUPLICATE -> policy.stringArrayDuplicate(field,index);
            case UP -> policy.stringArrayMove(field,index,index-1);
            case DOWN -> policy.stringArrayMove(field,index,index+1);
        },false);
    }
}
