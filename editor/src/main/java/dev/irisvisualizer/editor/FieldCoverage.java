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
            case "dimensions" -> List.of("dimension", "structurePlacement", "nativeStructureSource", "jigsawConfiguration", "nativeStructureAdjustment", "structureStilt", "structureTerrain", "structureYBand", "dimensionMode", "importedFeatureBiomeRule", "structureSetFrequency", "subterrainFeature", "imageMapBinding", "imageMapMask", "importedStructures", "importedFeatures", "caveProfile", "styledRange", "caveModule", "range", "riverPolicy", "hydrology", "riverHydrology", "riverRouting", "riverRegional", "riverGeometry", "riverMeanders", "riverSurfaceShape", "riverChannelShape", "riverDropShape", "riverBank3D", "surfaceRiver", "surfaceRiverSource", "surfaceRiverChannel", "surfaceRiverBank", "surfaceRiverFlow", "surfaceRiverMouth", "surfaceRiverErosion", "surfaceRiverBed", "riverExcavation", "surfaceRiverPonds", "surfaceRiverPond", "undergroundRiver", "undergroundRiverSource", "riverGrottos", "coastalGrotto", "inlandGrotto", "seaCaves", "riverMaterial", "materialPalette", "blockData", "riverProfile", "standingSurfacePool", "deepFluid", "noiseStyle", "oreGenerator", "depositGenerator", "depositVariant", "staticObject", "staticObjectRotation", "position", "objectReplace");
            case "biomes" -> List.of("biome", "proceduralObjects", "proceduralTree", "treeDecorator", "treeSecondaryLeaf", "ruinDecorator", "treeCanopy", "treeLayer", "treeBranches", "treeSubBranches", "proceduralRuin", "proceduralFormation", "proceduralCoral", "proceduralFungus", "proceduralCrystal", "structurePlacement", "nativeStructureSource", "jigsawConfiguration", "biomeGeneratorLink", "terrain3D", "caveProfile", "styledRange", "caveModule", "range", "riverPolicy", "biomePaletteLayer", "slopeClip", "blockData", "noiseStyle", "floatingBiome", "objectPlacement", "objectTranslate", "objectScale", "objectRotation", "axisRotation", "objectLimit", "stiltSettings", "vacuumSettings", "objectReplace", "materialPalette", "oreGenerator", "depositGenerator", "depositVariant", "decorator");
            case "image-maps" -> List.of("imageMap", "imageMapOrigin");
            case "blocks" -> List.of("blockData");
            case "expressions" -> List.of("expression", "expressionVariable", "expressionFunction", "noiseStyle");
            case "generators" -> List.of("generator", "interpolator", "noise", "noiseStyle");
            case "regions" -> List.of("region", "proceduralObjects", "proceduralTree", "treeDecorator", "treeSecondaryLeaf", "ruinDecorator", "treeCanopy", "treeLayer", "treeBranches", "treeSubBranches", "proceduralRuin", "proceduralFormation", "proceduralCoral", "proceduralFungus", "proceduralCrystal", "structurePlacement", "nativeStructureSource", "jigsawConfiguration", "caveProfile", "styledRange", "caveModule", "range", "riverPolicy", "materialPalette", "blockData", "noiseStyle", "oreGenerator", "depositGenerator", "depositVariant");
            default -> List.of();
        };
    }
}
