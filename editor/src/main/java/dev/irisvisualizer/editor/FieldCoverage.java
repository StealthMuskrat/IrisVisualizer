package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Source-inventory metadata only. This report never authorizes edits or gates file operations. */
public final class FieldCoverage {
    private static final Properties DATA = new Properties();
    static {
        try (var input = FieldCoverage.class.getResourceAsStream("/field-coverage.properties")) {
            if (input == null) throw new IOException("Missing field coverage report");
            DATA.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Field(String name, String classification, String reason, boolean implemented) {}
    public static List<Field> fields(String group) {
        String names = DATA.getProperty(group + ".fields", "");
        if (names.isEmpty()) return List.of();
        return Arrays.stream(names.split(",")).map(name -> {
            String prefix = group + "." + name;
            return new Field(name, DATA.getProperty(prefix + ".classification", "unclassified"),
                    DATA.getProperty(prefix + ".reason", "Awaiting classification."), Boolean.parseBoolean(DATA.getProperty(prefix + ".implemented")));
        }).toList();
    }
    public static List<String> groups(String path) {
        String base = ResourceTypes.base(path);
        if (base == null) return List.of();
        return switch (base) {
            case "dimensions" -> List.of("dimension", "importedStructures", "importedFeatures", "caveProfile", "styledRange", "caveModule", "range", "riverPolicy", "hydrology", "riverHydrology", "riverRouting", "riverRegional", "riverGeometry", "riverMeanders", "riverSurfaceShape", "riverChannelShape", "riverDropShape", "riverBank3D", "surfaceRiver", "surfaceRiverSource", "surfaceRiverChannel", "surfaceRiverBank", "surfaceRiverFlow", "surfaceRiverMouth", "surfaceRiverErosion", "surfaceRiverBed", "riverExcavation");
            case "biomes" -> List.of("biome", "biomeGeneratorLink", "terrain3D", "caveProfile", "styledRange", "caveModule", "range", "riverPolicy");
            case "generators" -> List.of("generator", "interpolator", "noise", "noiseStyle");
            case "regions" -> List.of("region", "caveProfile", "styledRange", "caveModule", "range", "riverPolicy");
            default -> List.of();
        };
    }
}
