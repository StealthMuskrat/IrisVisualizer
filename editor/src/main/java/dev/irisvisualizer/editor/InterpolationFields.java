package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Exact enum choices from the pinned VolmLib declaration; no interpolation algorithms run here. */
public final class InterpolationFields {
    public static final List<String> EDITABLE = List.of("function");
    public static final List<String> OBJECT_FIELDS = List.of("interpolator");
    private static final Properties DEFINITIONS = new Properties();
    private static final List<String> CHOICES;
    static {
        try (var input = InterpolationFields.class.getResourceAsStream("/interpolation-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned interpolation definitions");
            DEFINITIONS.load(input);
            CHOICES = List.of(DEFINITIONS.getProperty("choices", "").split(","));
            if (CHOICES.isEmpty() || CHOICES.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*"))
                    || CHOICES.stream().distinct().count() != CHOICES.size() || !CHOICES.contains(defaultValue()))
                throw new IOException("Invalid pinned interpolation choices");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static List<String> choices() { return CHOICES; }
    public static String defaultValue() { return DEFINITIONS.getProperty("default"); }
    public static String description() { return DEFINITIONS.getProperty("description", ""); }
    public static String description(String value) { return DEFINITIONS.getProperty(value + ".description", ""); }
    private static void authorize(String path) {
        if (!GeneratorFields.generator(path)) throw new IllegalArgumentException("Interpolation is read-only");
    }
    public static byte[] enable(String path, byte[] original) {
        authorize(path);
        return new LosslessJson(original).enableStringPath("interpolator.function", defaultValue());
    }
    public static byte[] edit(String path, byte[] original, String value) {
        authorize(path);
        if (!CHOICES.contains(value)) throw new IllegalArgumentException("Choose a pinned interpolation method");
        return new LosslessJson(original).stringPath("interpolator.function", value);
    }
}
