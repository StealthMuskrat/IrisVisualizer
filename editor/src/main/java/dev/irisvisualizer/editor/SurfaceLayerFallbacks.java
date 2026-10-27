package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Literal surface fallback choices; biome inheritance is preserved without resolving world ownership. */
public final class SurfaceLayerFallbacks {
    public static final List<String> OBJECT_FIELDS = List.of("surfaceLayerFallback");
    private static final Properties DATA=new Properties();
    private static final List<String> CHOICES;
    static {
        try (var input=SurfaceLayerFallbacks.class.getResourceAsStream("/surface-fallback-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned surface fallback definitions");DATA.load(input);
            CHOICES=List.of(DATA.getProperty("choices","").split(","));
            if (CHOICES.isEmpty() || !CHOICES.contains(dimensionDefault()) || !"null".equals(DATA.getProperty("biome.default"))) throw new IOException("Unsupported fallback defaults");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static boolean biome(String path) {
        String base=ResourceTypes.base(path);if (!"dimensions".equals(base) && !"biomes".equals(base)) throw new IllegalArgumentException("Surface fallback is dimension/biome-only");return base.equals("biomes");
    }
    public static List<String> choices() { return CHOICES; }
    public static String dimensionDefault() { return DATA.getProperty("dimension.default"); }
    public static String description(String path) { return DATA.getProperty(biome(path) ? "biome.description" : "dimension.description",""); }
    public static String choiceHelp(String value) { if (!CHOICES.contains(value)) throw new IllegalArgumentException("Unknown fallback choice");return DATA.getProperty(value+".description",""); }
    public static byte[] set(String path,byte[] original,String choice) {
        boolean biome=biome(path);if (choice!=null && !CHOICES.contains(choice)) throw new IllegalArgumentException("Choose a pinned fallback option");
        if (choice==null && !biome) throw new IllegalArgumentException("Only biome fallbacks inherit");
        var document=new LosslessJson(original);
        if (biome) {
            if (choice==null && document.token("surfaceLayerFallback")==null) return document.sourceBytes();
            return document.nullableString("surfaceLayerFallback",choice);
        }
        return document.token("surfaceLayerFallback")==null ? document.enableStringPath("surfaceLayerFallback",choice) : document.stringValue("surfaceLayerFallback",choice);
    }
}
