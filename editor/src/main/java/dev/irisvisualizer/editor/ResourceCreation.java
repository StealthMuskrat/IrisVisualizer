package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Source-template creation with explicitly selected exact local resource references. */
public final class ResourceCreation {
    public static String base(String folder) throws IOException {
        if (folder == null || folder.contains("\\") || folder.contains(":")) throw new IOException("Unsupported resource folder");
        String[] parts = folder.split("/", -1);
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe resource folder");
        if (!Set.of("dimensions", "regions", "biomes", "generators", "image-maps", "blocks", "expressions").contains(parts[0])) throw new IOException("Creation supports dimension, region, biome, generator, image-map, block and expression JSON only");
        if (parts[0].equals("dimensions") && parts.length != 1) throw new IOException("Dimensions must stay flat");
        return parts[0];
    }
    public static String dependencyType(String folder) throws IOException {
        return switch (base(folder)) {
            case "dimensions" -> "regions";
            case "regions" -> "biomes";
            case "biomes" -> "generators";
            case "image-maps" -> "images";
            default -> null;
        };
    }
    public static List<String> choices(PackWorkspace workspace, String folder) throws IOException {
        String type = dependencyType(folder);
        if (type == null) return List.of();
        if (base(folder).equals("image-maps")) return ImageMapDefinitions.sources(workspace);
        return ResourceTypes.referenceKeys(workspace,type);
    }
    static byte[] bytes(PackWorkspace workspace, String folder, String name, String dependency) throws IOException {
        String base = base(folder);
        if (dependencyType(folder) != null && !choices(workspace, folder).contains(dependency))
            throw new IOException("Choose an exact existing " + dependencyType(folder) + " key for the new resource");
        if (base.equals("image-maps")) return ImageMapDefinitions.template(workspace,dependency);
        if (base.equals("blocks")) return BlockDefinitions.template();
        if (base.equals("expressions")) return ExpressionDefinitions.template();
        byte[] bytes = StarterPack.resourceTemplate(base, name);
        LosslessJson document = new LosslessJson(bytes);
        switch (base) {
            case "dimensions" -> bytes = document.stringArrayPath("regions", 0, dependency);
            case "regions" -> {
                for (String field : List.of("landBiomes", "seaBiomes", "shoreBiomes"))
                    bytes = new LosslessJson(bytes).stringArrayPath(field, 0, dependency);
            }
            case "biomes" -> bytes = document.objectArrayEdit("generators", 0, object -> object.stringValue("generator", dependency));
            default -> { }
        }
        return bytes;
    }
}
