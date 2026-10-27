package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Audited UI policy: these dimension fields affect generation; all other fields remain read-only. */
public final class DimensionFields {
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = DimensionFields.class.getResourceAsStream("/dimension-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned field definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static final List<String> EDITABLE = List.of("fluidHeight", "dimensionAngleDeg", "landChance", "objectSurfaceSupportBuffer",
            "bedrock", "decorate", "carvingEnabled", "postProcessing", "postProcessingSlabs",
            "postProcessingWalls", "requireObjectSurfaceSupport", "importedStructures.undergroundYShift",
            "importedStructures.datapackOverrides", "importedFeatures.enabled",
            "importedStructures.disabled", "importedStructures.disabledExact",
            "importedFeatures.disabled", "importedFeatures.disabledExact", "regions", "biomeZoom", "landZoom", "seaZoom", "continentZoom", "regionZoom", "coordFractureZoom", "coordFractureDistance", "terrainSamplingStep", "biomeBoundsSamplingStep", "caveDensitySamplingStep", "caveLavaHeight", "upperDimensionGap", "upperDimensionCarving", "upperDimensionObjects", "upperObjectsForcePlace");
    /** Report unknown root fields and unknown children of the supported object groups. */
    public static List<String> readOnlyPaths(LosslessJson document) {
        List<String> paths = new ArrayList<>();
        for (String field : document.fields()) {
            if (EDITABLE.contains(field)) continue;
            if (SurfaceLayerFallbacks.OBJECT_FIELDS.contains(field)) continue;
            if (DimensionPalettes.OBJECT_FIELDS.contains(field)) { paths.addAll(DimensionPalettes.readOnlyPaths(document,field));continue; }
            if (DimensionHeights.OBJECT_FIELDS.contains(field)) { paths.addAll(DimensionHeights.readOnlyPaths(document));continue; }
            if (HydrologyFields.ROOT_FIELDS.contains(field)) { paths.addAll(HydrologyFields.readOnlyPaths(document)); continue; }
            if (RiverPolicyFields.OBJECT_FIELDS.contains(field)) {
                try {
                    var policy = new LosslessJson(document.token(field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    for (String child : policy.fields()) {
                        if (RiverPolicyFields.EDITABLE.contains(child)) continue;
                        if (RiverBiomeLists.EDITABLE.contains(child) || RiverIdLists.EDITABLE.contains(child)) {
                            if (!"null".equals(policy.token(child))) {
                                try { policy.stringArrayPath(child); }
                                catch (IllegalArgumentException mismatch) { paths.add(field+"."+child); }
                            }
                        } else paths.add(field+"."+child);
                    }
                } catch (IllegalArgumentException mismatch) { paths.add(field); }
                continue;
            }
            if (CaveProfileFields.OBJECT_FIELDS.contains(field)) {
                try {
                    LosslessJson object = new LosslessJson(document.token(field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    for (String child : object.fields()) {
                        if (CaveProfileFields.EDITABLE.contains(child)) continue;
                        if (CavePlacementFields.ENUM_FIELDS.contains(child)) continue;
                        if (child.equals("verticalRange")) {
                            try {
                                LosslessJson range = CavePlacementFields.range(object);
                                for (String member : range.fields()) if (!CavePlacementFields.RANGE_MEMBERS.contains(member)) paths.add(field+"."+child+"."+member);
                            } catch (IllegalArgumentException mismatch) { paths.add(field+"."+child); }
                        } else if (CaveThresholdFields.OBJECT_FIELDS.contains(child)) {
                            try {
                                LosslessJson threshold = CaveThresholdFields.object(object);
                                for (String member : threshold.fields()) {
                                    if (member.equals("style")) {
                                        try {
                                            LosslessJson style = new LosslessJson(threshold.token(member).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                            for (String setting : style.fields()) if (!NoiseStyleFields.EDITABLE.contains(setting)) paths.add(field+"."+child+".style."+setting);
                                        } catch (IllegalArgumentException mismatch) { paths.add(field+"."+child+".style"); }
                                    } else if (!CaveThresholdFields.EDITABLE.contains(member)) paths.add(field+"."+child+"."+member);
                                }
                            } catch (IllegalArgumentException mismatch) { paths.add(field+"."+child); }
                        } else if (CaveModules.LIST_FIELDS.contains(child)) {
                            for (String path : CaveModules.readOnlyPaths(object)) paths.add(field+"."+path);
                        } else if (NoiseStyleFields.CAVE_FIELDS.contains(child)) {
                            try {
                                LosslessJson style=new LosslessJson(object.token(child).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                for (String member:style.fields()) if (!NoiseStyleFields.EDITABLE.contains(member)) paths.add(field+"."+child+"."+member);
                            } catch (IllegalArgumentException mismatch) { paths.add(field+"."+child); }
                        } else paths.add(field+"."+child);
                    }
                } catch (IllegalArgumentException mismatch) { paths.add(field); }
                continue;
            }
            if (NoiseStyleFields.DIMENSION_FIELDS.contains(field)) {
                try {
                    LosslessJson object = new LosslessJson(document.token(field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    for (String child : object.fields()) if (!NoiseStyleFields.EDITABLE.contains(child)) paths.add(field+"."+child);
                } catch (IllegalArgumentException mismatch) { paths.add(field); }
                continue;
            }
            if (EDITABLE.stream().noneMatch(known -> known.startsWith(field + "."))) { paths.add(field); continue; }
            try {
                LosslessJson object = new LosslessJson(document.token(field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for (String child : object.fields()) if (!EDITABLE.contains(field + "." + child)) paths.add(field + "." + child);
            } catch (IllegalArgumentException mismatch) { paths.add(field); }
        }
        return List.copyOf(paths);
    }
    public static boolean dimension(String path) { return "dimensions".equals(ResourceTypes.base(path)); }
    private static void authorize(String path, String field) {
        if (!dimension(path) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
    }
    public static boolean arrayField(String field) { return "stringArray".equals(DEFINITIONS.getProperty(field + ".type")) || "regionArray".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static List<String> regionKeys(PackWorkspace workspace) {
        return ResourceTypes.referenceKeys(workspace,"regions");
    }
    public static byte[] editArray(String path, byte[] original, String field, int index, String value, PackWorkspace workspace) {
        if (!field.equals("regions")) return editArray(path, original, field, index, value);
        authorize(path, field); var document = new LosslessJson(original);
        if (value != null && !regionKeys(workspace).contains(value)) throw new IllegalArgumentException("Choose an exact region key present in this pack");
        if (value == null && document.stringArrayPath(field).size() == 1) throw new IllegalArgumentException("Keep at least one region in this required list");
        return document.stringArrayPath(field, index, value);
    }
    public static byte[] editArray(String path, byte[] original, String field, int index, String value) {
        authorize(path, field);
        if (!arrayField(field) || field.equals("regions")) throw new IllegalArgumentException("Field requires its typed reference control");
        // Syntax only: registries are not available to this editor. Existing strings remain untouched.
        if (value != null) {
            boolean prefixes = "true".equals(DEFINITIONS.getProperty(field + ".prefixes"));
            String pattern = prefixes ? "[a-z0-9_.-]+:[a-z0-9/._-]*" : "[a-z0-9_.-]+:[a-z0-9/._-]+";
            if (!value.matches(pattern)) throw new IllegalArgumentException("Enter a lowercase namespace:key" + (prefixes ? " (namespace: is also allowed)" : ""));
        }
        return new LosslessJson(original).stringArrayPath(field, index, value);
    }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
        String value = DEFINITIONS.getProperty(field + ".default");
        return booleanField(field) || arrayField(field) ? value : new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
    }
    public static byte[] enable(String path, byte[] original, String field) {
        authorize(path, field);
        return new LosslessJson(original).enablePath(field, defaultToken(field));
    }
    public static byte[] editBoolean(String path, byte[] original, String field, boolean value) {
        authorize(path, field);
        if (!booleanField(field)) throw new IllegalArgumentException("Numeric field requires a number");
        return new LosslessJson(original).boolPath(field, value);
    }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String options(String field) { return DEFINITIONS.getProperty(field + ".options", ""); }
    public static double min(String field) { return Double.parseDouble(DEFINITIONS.getProperty(field + ".minimum")); }
    public static double max(String field) { return Double.parseDouble(DEFINITIONS.getProperty(field + ".maximum")); }
    public static byte[] edit(String path, byte[] original, String field, double value) {
        authorize(path, field);
        if (booleanField(field) || arrayField(field)) throw new IllegalArgumentException("Field requires its typed control");
        if (!Double.isFinite(value) || value < min(field) || value > max(field)
                || (DEFINITIONS.getProperty(field + ".type").equals("int") && value != Math.rint(value)))
            throw new IllegalArgumentException("Value violates the pinned Iris field constraints");
        if (!options(field).isEmpty() && Arrays.stream(options(field).split(",")).noneMatch(option -> Double.parseDouble(option) == value))
            throw new IllegalArgumentException("Choose one of the pinned Iris options: " + options(field));
        return new LosslessJson(original).numberPath(field, value);
    }
}
