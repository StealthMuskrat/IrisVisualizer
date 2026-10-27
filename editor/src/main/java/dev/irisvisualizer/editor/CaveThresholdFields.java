package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Density threshold settings only; this editor never evaluates noise or carves caves. */
public final class CaveThresholdFields {
    public static final List<String> OBJECT_FIELDS = List.of("densityThreshold");
    public static final List<String> EDITABLE = List.of("min", "max", "style");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = CaveThresholdFields.class.getResourceAsStream("/cave-threshold-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned cave threshold definitions");
            DEFINITIONS.load(input);
            for (boolean contextual : List.of(false, true)) {
                double min = Double.parseDouble(defaultBound("min", contextual)), max = Double.parseDouble(defaultBound("max", contextual));
                if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) throw new IOException("Invalid cave threshold defaults");
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) {
        String base = ResourceTypes.base(path);
        if (base == null || !CaveProfileFields.CONTEXTS.contains(base)) throw new IllegalArgumentException("Cave threshold is read-only");
    }
    public static String description() { return DEFINITIONS.getProperty("description", ""); }
    public static String styleDescription() { return DEFINITIONS.getProperty("style.description", ""); }
    public static String defaultBound(String member, boolean absentRange) {
        if (!CavePlacementFields.RANGE_MEMBERS.contains(member)) throw new IllegalArgumentException("Unsupported threshold bound");
        return new BigDecimal(DEFINITIONS.getProperty((absentRange ? "context." : "") + member + ".default")).stripTrailingZeros().toPlainString();
    }
    public static String defaultStyle(boolean absentRange) { return DEFINITIONS.getProperty((absentRange ? "context." : "") + "style.default"); }
    public static String draftPrefix(String context) {
        CavePlacementFields.draftPrefix(context); // Same audited owner policy, separate draft scope.
        return "caveProfile#" + context + ".densityThreshold.";
    }
    static LosslessJson object(LosslessJson profile) {
        String token = profile.token("densityThreshold");
        return token == null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] enable(String path, byte[] original) {
        authorize(path);
        return new LosslessJson(original).objectEdit("caveProfile", profile -> {
            if (profile.token("densityThreshold") != null) throw new IllegalArgumentException("Density threshold is already present or unsupported");
            return profile.objectEdit("densityThreshold", range -> {
                byte[] min = range.enable("min", defaultBound("min", true));
                byte[] max = new LosslessJson(min).enable("max", defaultBound("max", true));
                return new LosslessJson(max).enableStringPath("style.style", defaultStyle(true));
            }, true);
        }, false);
    }
    public static byte[] editRange(String path, byte[] original, String minimum, String maximum) {
        authorize(path);
        double min = Double.parseDouble(minimum), max = Double.parseDouble(maximum);
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max)
            throw new IllegalArgumentException("Enter finite threshold bounds with minimum at most maximum");
        return new LosslessJson(original).objectEdit("caveProfile", profile -> profile.objectEdit("densityThreshold", range -> {
            byte[] next = CavePlacementFields.bound(range, "min", min);
            return CavePlacementFields.bound(new LosslessJson(next), "max", max);
        }, false), false);
    }
}
