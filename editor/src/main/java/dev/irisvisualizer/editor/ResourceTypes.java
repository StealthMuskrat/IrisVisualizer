package dev.irisvisualizer.editor;

/** Currently supported editable JSON resource families; nested files inherit their base type. */
public final class ResourceTypes {
    public static String base(String path) {
        if (path == null || !path.endsWith(".json") || path.contains("\\") || path.contains(":")) return null;
        String[] parts = path.split("/", -1);
        if (parts.length < 2) return null;
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        if (!java.util.Set.of("dimensions", "regions", "biomes", "generators").contains(parts[0])) return null;
        if (parts[0].equals("dimensions") && parts.length != 2) return null;
        return parts[0];
    }
    public static String target(String source, String key) throws java.io.IOException {
        String base = base(source);
        if (base == null) throw new java.io.IOException("This resource type is read-only for duplication");
        if (key == null || !key.matches("(?:[a-z0-9_-]+/)*[a-z0-9_-]+")) throw new java.io.IOException("Use a relative resource key with lowercase letters, digits, _ or -; omit .json");
        if (base.equals("dimensions") && key.contains("/")) throw new java.io.IOException("Dimension files must stay directly inside dimensions/");
        return base + "/" + key + ".json";
    }
}
