package dev.irisvisualizer.editor;

/** Supported JSON families and explicitly permitted opaque asset file operations. */
public final class ResourceTypes {
    public static boolean referenceKey(String key) {
        return key != null && !key.isBlank() && !key.contains("\\") && !key.contains(":")
                && java.util.Arrays.stream(key.split("/",-1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }
    /** Syntax and exact file presence only; no target-content validation or loader alias resolution. */
    public static java.util.List<String> referenceKeys(PackWorkspace workspace, String family) {
        if (!java.util.Set.of("dimensions","regions","biomes","generators","image-maps","blocks","expressions","structures","jigsaw-pools","jigsaw-pieces").contains(family))
            throw new IllegalArgumentException("Unsupported resource reference family");
        return workspace.paths().stream().filter(path -> family.equals(base(path)))
                .map(path -> path.substring(family.length()+1,path.length()-5)).filter(ResourceTypes::referenceKey).sorted().toList();
    }
    public static String base(String path) {
        if (path == null || !path.endsWith(".json") || path.contains("\\") || path.contains(":")) return null;
        String[] parts = path.split("/", -1);
        if (parts.length < 2) return null;
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        if (!java.util.Set.of("dimensions", "regions", "biomes", "generators", "image-maps", "blocks","expressions","structures","jigsaw-pools","jigsaw-pieces").contains(parts[0])) return null;
        if (parts[0].equals("dimensions") && parts.length != 2) return null;
        return parts[0];
    }
    public static String fileFamily(String path) {
        String json = base(path); if (json != null) return json;
        if (path == null || path.contains("\\") || path.contains(":")) return null;
        String[] parts = path.split("/", -1);
        if (parts.length < 2) return null;
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (parts[0].equals("objects") && lower.endsWith(".iob")) return "objects";
        if (parts[0].equals("images") && AssetImage.supportedPath(path)) return "images";
        return null;
    }
    public static String extension(String path) { return path.substring(path.lastIndexOf('.')); }
    public static boolean folderFamily(String folder) {
        if (folder == null || folder.contains("\\") || folder.contains(":")) return false;
        String[] parts = folder.split("/", -1);
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return java.util.Set.of("regions", "biomes", "generators", "image-maps", "blocks","expressions","structures","jigsaw-pools","jigsaw-pieces", "objects", "images").contains(parts[0]);
    }
    public static String target(String source, String key) throws java.io.IOException {
        String base = fileFamily(source);
        if (base == null) throw new java.io.IOException("This resource type is read-only for duplication");
        if (key == null || !key.matches("(?:[a-z0-9_-]+/)*[a-z0-9_-]+")) throw new java.io.IOException("Use a relative resource key with lowercase letters, digits, _ or -; omit the file extension");
        if (base.equals("dimensions") && key.contains("/")) throw new java.io.IOException("Dimension files must stay directly inside dimensions/");
        return base + "/" + key + extension(source);
    }
}
