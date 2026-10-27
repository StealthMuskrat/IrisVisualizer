package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Audited scalar controls for composite entries and the cliff/placement height noise objects; no noise sampling or preview generation. */
public final class NoiseFields {
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = NoiseFields.class.getResourceAsStream("/noise-fields.properties");
             var generatorInput = NoiseFields.class.getResourceAsStream("/generator-fields.properties")) {
            if (input == null || generatorInput == null) throw new IOException("Missing pinned generator definitions");
            DEFINITIONS.load(input);
            var generator = new Properties(); generator.load(generatorInput);
            if (!"IrisNoiseGenerator".equals(generator.getProperty("cliffHeightGenerator.type")))
                throw new IOException("Unsupported pinned cliff noise object type");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static final List<String> EDITABLE = List.of("zoom", "negative", "opacity", "offsetX", "offsetY", "offsetZ", "seed", "parametric", "bezier", "sinCentered", "exponent", "enabled", "octaves");
    public static final List<String> OBJECT_FIELDS = List.of("cliffHeightGenerator");
    public record Target(String objectField, int index, FloatingPlacementOverrides.Context heightmapContext) {
        public Target(String objectField,int index) { this(objectField,index,null); }
        public Target {
            if (heightmapContext!=null) { if (!"heightmap".equals(objectField) || index!=-1) throw new IllegalArgumentException("Unsupported placement heightmap target"); }
            else if (!("composite".equals(objectField) && index >= 0) && !(OBJECT_FIELDS.contains(objectField) && index == -1))
                throw new IllegalArgumentException("Unsupported noise object target");
        }
        public static Target heightmap(FloatingPlacementOverrides.Context context) { return new Target("heightmap",-1,java.util.Objects.requireNonNull(context)); }
        public boolean heightmap() { return heightmapContext!=null; }
        public String resourceBase() { return heightmap() ? "biomes" : "generators"; }
        public static Target composite(int index) { return new Target("composite", index); }
        public static Target cliffHeight() { return new Target("cliffHeightGenerator", -1); }
        public String draftPrefix() { return heightmap() ? heightmapContext.heightmapPrefix() : index < 0 ? objectField + "#" : objectField + "#" + index + "."; }
    }
    public static boolean generator(String path) { return "generators".equals(ResourceTypes.base(path)); }
    private static void authorize(String path, Target target, String field) {
        if (!target.resourceBase().equals(ResourceTypes.base(path)) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Generator field is read-only");
    }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String constraints(String field) {
        if ("long".equals(DEFINITIONS.getProperty(field + ".type"))) return "Signed 64-bit integer (exact)";
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        return ("int".equals(DEFINITIONS.getProperty(field + ".type")) ? "Exact signed 32-bit integer" : "Finite number") + (min == null ? "" : ", minimum " + min) + (max == null ? "" : ", maximum " + max);
    }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
        String token = DEFINITIONS.getProperty(field + ".default");
        return booleanField(field) ? token : new java.math.BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    // Existing composite APIs keep their nonnegative index contract.
    public static byte[] enable(String path, byte[] original, int index, String field) { return enable(path, original, Target.composite(index), field); }
    public static byte[] editText(String path, byte[] original, int index, String field, String input) { return editText(path, original, Target.composite(index), field, input); }
    public static byte[] editBoolean(String path, byte[] original, int index, String field, boolean value) { return editBoolean(path, original, Target.composite(index), field, value); }
    public static byte[] enable(String path, byte[] original, Target target, String field) {
        authorize(path, target, field);
        return editObject(original, target, object -> object.enable(field, defaultToken(field)), true);
    }
    public static byte[] editText(String path, byte[] original, Target target, String field, String input) {
        authorize(path, target, field);
        if (booleanField(field)) throw new IllegalArgumentException("Boolean field requires its toggle");
        if ("long".equals(DEFINITIONS.getProperty(field + ".type"))) {
            long value = Long.parseLong(input);
            return editObject(original, target, object -> object.integer(field, value), false);
        }
        double value;
        try { value = "int".equals(DEFINITIONS.getProperty(field + ".type")) ? new java.math.BigDecimal(input).intValueExact() : Double.parseDouble(input); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a valid noise number; integer fields require exact signed 32-bit values",invalid); }
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        if (!Double.isFinite(value) || (min != null && value < Double.parseDouble(min)) || (max != null && value > Double.parseDouble(max)))
            throw new IllegalArgumentException("Value violates pinned Iris generator constraints");
        return editObject(original, target, object -> object.number(field, value), false);
    }
    public static byte[] editBoolean(String path, byte[] original, Target target, String field, boolean value) {
        authorize(path, target, field); if (!booleanField(field)) throw new IllegalArgumentException("Numeric field requires a number");
        return editObject(original, target, object -> object.bool(field, value), false);
    }
    static byte[] editObject(byte[] original, Target target, java.util.function.Function<LosslessJson, byte[]> operation, boolean create) {
        if (target.heightmap()) return FloatingPlacementOverrides.editHeightmap(original,target.heightmapContext(),operation);
        var document = new LosslessJson(original);
        if (target.index() >= 0) return document.objectArrayEdit(target.objectField(), target.index(), operation);
        return document.objectEdit(target.objectField(), operation, create);
    }
}
