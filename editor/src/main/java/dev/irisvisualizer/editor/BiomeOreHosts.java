package dev.irisvisualizer.editor;

import java.util.List;

/** Null/absence inherits deposit surface hosts; an authored empty list forbids exterior-surface ore hosts. */
public final class BiomeOreHosts {
    public static final List<String> LIST_FIELDS = List.of("surfaceOreReplaceableBlocks");
    private static final String FIELD=LIST_FIELDS.getFirst();
    static { DepositVariants.verifyHostDefinitions(); }
    public static String prefix() { return "biomeOreHosts#surface."; }
    private static void authorize(String path) { if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Surface ore hosts are biome-only"); }
    private static void value(String value) { if (value==null || !value.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) throw new IllegalArgumentException("Enter a literal block id without properties; registry existence is unchecked"); }
    static List<String> entries(LosslessJson root) { String token=root.token(FIELD);return token==null || token.equals("null") ? null : root.stringArrayPath(FIELD); }
    public static byte[] inherit(String path,byte[] original) { authorize(path);return new LosslessJson(original).nullableStringArray(FIELD,true); }
    public static byte[] restrict(String path,byte[] original) { authorize(path);return new LosslessJson(original).nullableStringArray(FIELD,false); }
    public static byte[] set(String path,byte[] original,int index,String value) {
        authorize(path);value(value);var root=new LosslessJson(original);var entries=root.stringArrayPath(FIELD);if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing host");return root.stringArrayPath(FIELD,index,value);
    }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String value) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a host action");if (action==RiverProfiles.Action.ADD) value(value);var root=new LosslessJson(original);var entries=root.stringArrayPath(FIELD);
        if (action==RiverProfiles.Action.ADD) return root.stringArrayPath(FIELD,entries.size(),value);
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing host");
        return switch(action) {
            case DUPLICATE -> root.stringArrayDuplicate(FIELD,index);
            case REMOVE -> root.stringArrayPath(FIELD,index,null);
            case UP -> root.stringArrayMove(FIELD,index,index-1);
            case DOWN -> root.stringArrayMove(FIELD,index,index+1);
            default -> throw new IllegalArgumentException("Unsupported host action");
        };
    }
}
