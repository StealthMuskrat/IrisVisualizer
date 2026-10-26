package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** UI-owned pack creation from the pinned official Studio starter, without engine initialization. */
public final class StarterPack {
    public static String normalizeName(String value) throws IOException {
        if (value == null) throw new IOException("Pack name is required");
        String name = value.trim().toLowerCase(Locale.ROOT);
        if (name.length() > 64 || !name.matches("[a-z0-9_-]+"))
            throw new IOException("Use 1–64 letters a-z, digits, underscores or hyphens for the pack key");
        return name;
    }
    public static PackWorkspace create(String value) throws IOException {
        String name = normalizeName(value);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("dimensions/" + name + ".json", template("dimension.json").replace("%s", name).getBytes(StandardCharsets.UTF_8));
        files.put("regions/starter.json", template("region.json").getBytes(StandardCharsets.UTF_8));
        files.put("biomes/starter.json", template("biome.json").getBytes(StandardCharsets.UTF_8));
        files.put("generators/flat.json", template("generator.json").getBytes(StandardCharsets.UTF_8));
        return PackWorkspace.newPack(files);
    }
    private static String template(String name) throws IOException {
        try (var input = StarterPack.class.getResourceAsStream("/starter/" + name)) {
            if (input == null) throw new IOException("Missing pinned starter template: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
