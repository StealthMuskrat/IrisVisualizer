package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Nullable river overrides only; inheritance resolution and river generation are outside the editor. */
public final class RiverPolicyFields {
    public static final List<String> OBJECT_FIELDS = List.of("riverPolicy");
    public static final List<String> EDITABLE = List.of("surfaceSourceDensity", "surfaceSourceSpacing", "surfaceTributaries", "surfaceInlandOutlets", "surfaceCoastalOutlets", "surfaceMinimumCourseLength", "surfaceMaximumIncision", "placement", "routing", "outletAdmission", "widthMultiplier", "depthMultiplier", "incisionMultiplier", "routingMultiplier", "bankMultiplier", "shoreBiomeWidth", "shoreWidth", "erosion", "confined");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = RiverPolicyFields.class.getResourceAsStream("/river-policy-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned river policy definitions");
            DEFINITIONS.load(input);
            if (!"{}".equals(DEFINITIONS.getProperty("dimensions.default")) || !"null".equals(DEFINITIONS.getProperty("regions.default")) || !"null".equals(DEFINITIONS.getProperty("biomes.default")))
                throw new IOException("Unsupported river policy owner defaults");
            for (String field : EDITABLE) {
                if (!"null".equals(DEFINITIONS.getProperty(field + ".default"))) throw new IOException("River override must remain nullable");
                if (enumField(field)) {
                    var choices = choices(field);
                    if (choices.isEmpty() || choices.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*")) || choices.stream().distinct().count() != choices.size())
                        throw new IOException("Invalid pinned river mode choices");
                } else if (!List.of("Double", "Integer", "Boolean").contains(DEFINITIONS.getProperty(field + ".type", "")))
                    throw new IOException("Missing river override type");
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static String authorize(String path) {
        String base = ResourceTypes.base(path);
        if (base == null || !CaveProfileFields.CONTEXTS.contains(base)) throw new IllegalArgumentException("River policy is read-only");
        return base;
    }
    private static void field(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported river override");
    }
    public static String description(String path) { return DEFINITIONS.getProperty(authorize(path) + ".description", ""); }
    public static String help(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static boolean booleanField(String field) { return "Boolean".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static boolean integerField(String field) { return "Integer".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static boolean enumField(String field) { return DEFINITIONS.containsKey(field + ".choices"); }
    public static List<String> choices(String field) {
        field(field);
        if (!enumField(field)) throw new IllegalArgumentException("River field is not a mode");
        return List.of(DEFINITIONS.getProperty(field + ".choices", "").split(","));
    }
    public static String choiceDescription(String field, String choice) { return DEFINITIONS.getProperty(field + "." + choice + ".description", ""); }
    public static String constraints(String field) {
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        return (integerField(field) ? "Signed 32-bit integer" : "Finite number") + (min == null ? "" : ", minimum " + min) + (max == null ? "" : ", maximum " + max);
    }
    public static String draftPrefix(String context) {
        if (context == null || !CaveProfileFields.CONTEXTS.contains(context)) throw new IllegalArgumentException("Unsupported river policy owner");
        return "riverPolicy#" + context + ".";
    }
    static boolean numericStored(String token) { return token == null || token.equals("null") || LosslessJson.numericToken(token); }
    static LosslessJson object(String path, LosslessJson document) {
        String context = authorize(path), token = document.token("riverPolicy");
        return token == null || token.equals("null") && "null".equals(DEFINITIONS.getProperty(context + ".default")) ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] enablePolicy(String path, byte[] original) {
        String context = authorize(path); var document = new LosslessJson(original);
        String token = document.token("riverPolicy");
        if (token != null && !(token.equals("null") && "null".equals(DEFINITIONS.getProperty(context + ".default"))))
            throw new IllegalArgumentException("River policy is already present or unsupported");
        return document.enableObject("riverPolicy");
    }
    public static byte[] setText(String path, byte[] original, String field, String input) {
        authorize(path); field(field);
        if (booleanField(field) || enumField(field) || input == null) throw new IllegalArgumentException("Enter a numeric river override");
        double value;
        if (integerField(field)) {
            try { value = new java.math.BigDecimal(input).intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer", invalid); }
        } else value = Double.parseDouble(input);
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        if (!Double.isFinite(value) || min != null && value < Double.parseDouble(min) || max != null && value > Double.parseDouble(max)
                || integerField(field) && (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE))
            throw new IllegalArgumentException("Value violates pinned river policy constraints");
        return new LosslessJson(original).objectEdit("riverPolicy", policy -> policy.nullableNumber(field, value), false);
    }
    public static byte[] setBoolean(String path, byte[] original, String field, Boolean value) {
        authorize(path); field(field);
        if (!booleanField(field)) throw new IllegalArgumentException("River override is not boolean");
        return new LosslessJson(original).objectEdit("riverPolicy", policy -> policy.nullableBoolean(field, value), false);
    }
    public static byte[] setMode(String path, byte[] original, String field, String value) {
        authorize(path); field(field);
        if (!enumField(field) || value != null && !choices(field).contains(value)) throw new IllegalArgumentException("Choose a pinned river mode");
        return new LosslessJson(original).objectEdit("riverPolicy", policy -> policy.nullableString(field, value), false);
    }
    public static byte[] reset(String path, byte[] original, String field) {
        authorize(path); field(field);
        if (booleanField(field)) return setBoolean(path, original, field, null);
        if (enumField(field)) return setMode(path, original, field, null);
        return new LosslessJson(original).objectEdit("riverPolicy", policy -> policy.nullableNumber(field, null), false);
    }
}
