package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Bounded controls on a noise object's style; no style factories or noise algorithms run. */
public final class NoiseStyleFields {
    public static final List<String> EDITABLE = List.of("style", "zoom", "exponent");
    public static final List<String> OBJECT_FIELDS = List.of("style");
    private static final Properties DEFINITIONS = new Properties();
    private static final List<String> CHOICES;
    static {
        try (var input = NoiseStyleFields.class.getResourceAsStream("/style-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned style definitions");
            DEFINITIONS.load(input);
            CHOICES = List.of(DEFINITIONS.getProperty("choices", "").split(","));
            if (CHOICES.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*"))
                    || CHOICES.stream().distinct().count() != CHOICES.size()
                    || !CHOICES.contains(noiseDefault()) || !CHOICES.contains(objectDefault()))
                throw new IOException("Invalid pinned noise style choices");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static List<String> choices() { return CHOICES; }
    public static String noiseDefault() { return DEFINITIONS.getProperty("noiseDefault"); }
    public static String objectDefault() { return DEFINITIONS.getProperty("objectDefault"); }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String constraints(String field) {
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        return "Finite number" + (min == null ? "" : ", minimum " + min) + (max == null ? "" : ", maximum " + max);
    }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field) || field.equals("style")) throw new IllegalArgumentException("Not a style scalar");
        return new java.math.BigDecimal(DEFINITIONS.getProperty(field + ".default")).stripTrailingZeros().toPlainString();
    }
    private static void authorize(String path, String field) {
        if (!GeneratorFields.generator(path) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Noise style field is read-only");
    }
    public static byte[] enable(String path, byte[] original, NoiseFields.Target target, String field) {
        authorize(path, field);
        return NoiseFields.editObject(original, target, noise -> {
            if (noise.token("style") == null) {
                // IrisNoiseGenerator defaults to IRIS, unlike an existing empty IrisGeneratorStyle (FLAT).
                byte[] seeded = noise.enableStringPath("style.style", noiseDefault());
                if (field.equals("style")) return seeded;
                noise = new LosslessJson(seeded);
            }
            return noise.objectEdit("style", style -> field.equals("style")
                    ? style.enableStringPath("style", objectDefault()) : style.enable(field, defaultToken(field)), false);
        }, true);
    }
    public static byte[] editStyle(String path, byte[] original, NoiseFields.Target target, String value) {
        authorize(path, "style");
        if (value == null || !CHOICES.contains(value)) throw new IllegalArgumentException("Choose a pinned noise style");
        return NoiseFields.editObject(original, target, noise -> noise.objectEdit("style", style -> style.stringValue("style", value), false), false);
    }
    public static byte[] editText(String path, byte[] original, NoiseFields.Target target, String field, String input) {
        authorize(path, field);
        if (field.equals("style")) throw new IllegalArgumentException("Use the noise style picker");
        double value = Double.parseDouble(input);
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        if (!Double.isFinite(value) || (min != null && value < Double.parseDouble(min)) || (max != null && value > Double.parseDouble(max)))
            throw new IllegalArgumentException("Value violates pinned Iris style constraints");
        return NoiseFields.editObject(original, target, noise -> noise.objectEdit("style", style -> style.number(field, value), false), false);
    }
    static LosslessJson object(LosslessJson noise) {
        String token = noise.token("style");
        return token == null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
}
