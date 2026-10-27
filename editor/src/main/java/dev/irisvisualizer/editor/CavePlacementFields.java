package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Cave bounds and placement defaults only; no profile selection or object placement runs. */
public final class CavePlacementFields {
    public static final List<String> EDITABLE = List.of("verticalRange", "defaultObjectAnchor", "defaultObjectPlaceMode");
    public static final List<String> ENUM_FIELDS = List.of("defaultObjectAnchor", "defaultObjectPlaceMode");
    public static final List<String> RANGE_MEMBERS = List.of("min", "max");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = CavePlacementFields.class.getResourceAsStream("/cave-placement-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned cave placement definitions");
            DEFINITIONS.load(input);
            for (String field : ENUM_FIELDS) {
                var choices = choices(field);
                if (choices.isEmpty() || choices.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*"))
                        || choices.stream().distinct().count() != choices.size()
                        || !(choices.contains(defaultValue(field)) || nullable(field)))
                    throw new IOException("Invalid pinned cave placement enum");
            }
            for (String member : RANGE_MEMBERS) {
                if (!Double.isFinite(Double.parseDouble(rangeDefault(member, false)))
                        || !Double.isFinite(Double.parseDouble(rangeDefault(member, true))))
                    throw new IOException("Invalid pinned cave range defaults");
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) {
        String base = ResourceTypes.base(path);
        if (base == null || !CaveProfileFields.CONTEXTS.contains(base)) throw new IllegalArgumentException("Cave placement is read-only");
    }
    private static void enumField(String field) {
        if (!ENUM_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported cave placement field");
    }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static List<String> choices(String field) {
        enumField(field); return List.of(DEFINITIONS.getProperty(field + ".choices", "").split(","));
    }
    public static String defaultValue(String field) { enumField(field); return DEFINITIONS.getProperty(field + ".default"); }
    public static boolean nullable(String field) { enumField(field); return field.equals("defaultObjectPlaceMode") && "null".equals(defaultValue(field)); }
    public static String choiceDescription(String field, String choice) { enumField(field); return DEFINITIONS.getProperty(field + "." + choice + ".description", ""); }
    public static String rangeDefault(String member, boolean absentRange) {
        if (!RANGE_MEMBERS.contains(member)) throw new IllegalArgumentException("Unsupported cave range member");
        return new BigDecimal(DEFINITIONS.getProperty((absentRange ? "verticalRange." : "range.") + member + ".default")).stripTrailingZeros().toPlainString();
    }
    public static String draftPrefix(String context) {
        if (!CaveProfileFields.CONTEXTS.contains(context)) throw new IllegalArgumentException("Unsupported cave owner");
        return "caveProfile#" + context + ".verticalRange.";
    }
    static LosslessJson range(LosslessJson profile) {
        String token = profile.token("verticalRange");
        return token == null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] enableRange(String path, byte[] original) {
        authorize(path);
        return new LosslessJson(original).objectEdit("caveProfile", profile -> {
            if (profile.token("verticalRange") != null) throw new IllegalArgumentException("Cave range is already present or unsupported");
            return profile.objectEdit("verticalRange", range -> {
                byte[] min = range.enable("min", rangeDefault("min", true));
                return new LosslessJson(min).enable("max", rangeDefault("max", true));
            }, true);
        }, false);
    }
    public static byte[] editRange(String path, byte[] original, String minimum, String maximum) {
        authorize(path);
        double min = Double.parseDouble(minimum), max = Double.parseDouble(maximum);
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max)
            throw new IllegalArgumentException("Enter finite cave bounds with minimum at most maximum");
        return new LosslessJson(original).objectEdit("caveProfile", profile -> profile.objectEdit("verticalRange", range -> {
            byte[] next = bound(range, "min", min);
            return bound(new LosslessJson(next), "max", max);
        }, false), false);
    }
    static byte[] bound(LosslessJson range, String field, double value) {
        String old = range.token(field);
        if (old == null) return range.enable(field, BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
        try {
            if (new BigDecimal(old).compareTo(BigDecimal.valueOf(value)) == 0) return range.sourceBytes();
        } catch (NumberFormatException mismatch) { throw new IllegalArgumentException("Stored cave range member is not numeric", mismatch); }
        return range.number(field, value);
    }
    static String enumValue(LosslessJson profile, String field) {
        enumField(field);
        if (nullable(field) && "null".equals(profile.token(field))) return null;
        return profile.stringToken(field);
    }
    public static byte[] assign(String path, byte[] original, String field, String choice) {
        authorize(path); enumField(field);
        if (choice == null ? !nullable(field) : !choices(field).contains(choice))
            throw new IllegalArgumentException("Choose a pinned cave placement mode");
        return new LosslessJson(original).objectEdit("caveProfile", profile -> {
            if (nullable(field)) return profile.nullableString(field, choice);
            return profile.token(field) == null ? profile.enableStringPath(field, choice) : profile.stringValue(field, choice);
        }, false);
    }
}
