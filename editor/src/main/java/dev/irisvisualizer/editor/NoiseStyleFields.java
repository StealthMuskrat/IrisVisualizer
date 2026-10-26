package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Bounded controls on source-audited style objects; no factories or noise algorithms run. */
public final class NoiseStyleFields {
    public static final List<String> EDITABLE = List.of("style", "zoom", "exponent", "cellularFrequency", "cellularZoom");
    public static final List<String> OBJECT_FIELDS = List.of("style");
    public static final List<String> DIMENSION_FIELDS = List.of("regionStyle", "continentalStyle", "landBiomeStyle", "shoreBiomeStyle", "seaBiomeStyle", "caveBiomeStyle");
    public static final List<String> BIOME_FIELDS = List.of("childStyle");
    public record Target(String resourceBase, String objectField, NoiseFields.Target noise) {
        public Target {
            if (resourceBase == null || objectField == null) throw new IllegalArgumentException("Unsupported style target");
            boolean valid = noise != null ? "generators".equals(resourceBase) && "style".equals(objectField)
                    : "dimensions".equals(resourceBase) && DIMENSION_FIELDS.contains(objectField)
                    || "biomes".equals(resourceBase) && BIOME_FIELDS.contains(objectField);
            if (!valid) throw new IllegalArgumentException("Unsupported style target");
        }
        public static Target noise(NoiseFields.Target target) { return new Target("generators", "style", java.util.Objects.requireNonNull(target)); }
        public static Target dimension(String field) { return new Target("dimensions", field, null); }
        public static Target biome(String field) { return new Target("biomes", field, null); }
        public String draftPrefix() { return noise == null ? "rootStyle#"+resourceBase+"."+objectField+"." : noise.draftPrefix()+"style."; }
    }
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
            for (String group : List.of("dimensions", "biomes"))
                for (String field : group.equals("dimensions") ? DIMENSION_FIELDS : BIOME_FIELDS)
                    if (!CHOICES.contains(DEFINITIONS.getProperty(group+"."+field+".default", "")))
                        throw new IOException("Missing pinned contextual style default");
            if (!"finiteNonnegative".equals(DEFINITIONS.getProperty("cellularFrequency.runtimeRule"))
                    || !"positiveFiniteReciprocalWhenActive".equals(DEFINITIONS.getProperty("cellularZoom.runtimeRule")))
                throw new IOException("Missing pinned cellular validation rules");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static List<String> choices() { return CHOICES; }
    public static String noiseDefault() { return DEFINITIONS.getProperty("noiseDefault"); }
    public static String objectDefault() { return DEFINITIONS.getProperty("objectDefault"); }
    public static String contextDefault(Target target) { return target.noise() == null ? DEFINITIONS.getProperty(target.resourceBase()+"."+target.objectField()+".default") : noiseDefault(); }
    public static String contextDescription(Target target) { return target.noise() == null ? DEFINITIONS.getProperty(target.resourceBase()+"."+target.objectField()+".description", "") : description("style"); }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String constraints(String field) {
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        return "Finite number" + (min == null ? "" : ", minimum " + min) + (max == null ? "" : ", maximum " + max)
                + (field.equals("cellularZoom") ? "; positive zoom required while cellular frequency > 0" : "");
    }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field) || field.equals("style")) throw new IllegalArgumentException("Not a style scalar");
        return new java.math.BigDecimal(DEFINITIONS.getProperty(field + ".default")).stripTrailingZeros().toPlainString();
    }
    private static void authorize(String path, Target target, String field) {
        if (!target.resourceBase().equals(ResourceTypes.base(path)) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Style field is read-only");
    }
    public static byte[] enable(String path, byte[] original, NoiseFields.Target target, String field) {
        return enable(path, original, Target.noise(target), field);
    }
    public static byte[] enable(String path, byte[] original, Target target, String field) {
        authorize(path, target, field);
        return editContainer(original, target, container -> {
            if (container.token(target.objectField()) == null) {
                byte[] seeded = container.enableStringPath(target.objectField()+".style", contextDefault(target));
                if (field.equals("style")) return seeded;
                container = new LosslessJson(seeded);
            }
            return container.objectEdit(target.objectField(), style -> {
                if (field.equals("style")) return style.enableStringPath("style",objectDefault());
                String token = defaultToken(field);
                validateCellularChange(style,field,Double.parseDouble(token));
                return style.enable(field,token);
            }, false);
        }, true);
    }
    public static byte[] editStyle(String path, byte[] original, NoiseFields.Target target, String value) {
        return editStyle(path, original, Target.noise(target), value);
    }
    public static byte[] editStyle(String path, byte[] original, Target target, String value) {
        authorize(path, target, "style");
        if (value == null || !CHOICES.contains(value)) throw new IllegalArgumentException("Choose a pinned noise style");
        return editContainer(original, target, container -> container.objectEdit(target.objectField(), style -> style.stringValue("style", value), false), false);
    }
    public static byte[] editText(String path, byte[] original, NoiseFields.Target target, String field, String input) {
        return editText(path, original, Target.noise(target), field, input);
    }
    public static byte[] editText(String path, byte[] original, Target target, String field, String input) {
        authorize(path, target, field);
        if (field.equals("style")) throw new IllegalArgumentException("Use the noise style picker");
        double value = Double.parseDouble(input);
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        if (!Double.isFinite(value) || (min != null && value < Double.parseDouble(min)) || (max != null && value > Double.parseDouble(max)))
            throw new IllegalArgumentException("Value violates pinned Iris style constraints");
        return editContainer(original, target, container -> container.objectEdit(target.objectField(), style -> {
            validateCellularChange(style,field,value);
            return style.number(field,value);
        }, false), false);
    }
    private static double numericOrDefault(LosslessJson style, String field) {
        String token = style.token(field);
        try { return Double.parseDouble(token == null ? defaultToken(field) : token); }
        catch (NumberFormatException mismatch) { throw new IllegalArgumentException("Stored "+field+" must be numeric",mismatch); }
    }
    private static void validateCellularChange(LosslessJson style, String field, double value) {
        if (!field.equals("cellularFrequency") && !field.equals("cellularZoom")) return;
        double frequency = field.equals("cellularFrequency") ? value : numericOrDefault(style,"cellularFrequency");
        if (!Double.isFinite(frequency) || frequency < 0)
            throw new IllegalArgumentException("Cellular frequency must be finite and nonnegative");
        if (frequency > 0) {
            double zoom = field.equals("cellularZoom") ? value : numericOrDefault(style,"cellularZoom");
            if (!Double.isFinite(zoom) || zoom <= 0 || !Double.isFinite(1D/zoom))
                throw new IllegalArgumentException("Cellular zoom must be positive and large enough for a finite reciprocal while frequency > 0");
        }
    }
    private static byte[] editContainer(byte[] original, Target target, java.util.function.Function<LosslessJson, byte[]> operation, boolean create) {
        return target.noise() == null ? operation.apply(new LosslessJson(original)) : NoiseFields.editObject(original, target.noise(), operation, create);
    }
    static LosslessJson object(LosslessJson noise) {
        return objectAt(noise, "style");
    }
    static LosslessJson object(LosslessJson container, Target target) {
        return objectAt(container, target.objectField());
    }
    private static LosslessJson objectAt(LosslessJson container, String field) {
        String token = container.token(field);
        return token == null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
}
