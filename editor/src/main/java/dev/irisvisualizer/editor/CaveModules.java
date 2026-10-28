package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Bounded editing of cave-density modules; no module blending or cave generation runs. */
public final class CaveModules {
    public static final List<String> LIST_FIELDS = List.of("modules");
    public static final List<String> EDITABLE = List.of("weight", "threshold", "invert");
    public static final List<String> OBJECT_FIELDS = List.of("style", "verticalRange");
    private static final Properties DEFINITIONS = new Properties();
    public enum Action { ADD, DUPLICATE, REMOVE, UP, DOWN }
    static {
        try (var input = CaveModules.class.getResourceAsStream("/cave-module-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned cave module definitions");
            DEFINITIONS.load(input);
            if (!"{}".equals(DEFINITIONS.getProperty("moduleDefault")) || !"[]".equals(DEFINITIONS.getProperty("listDefault")) || minimumEntries() < 0)
                throw new IOException("Unsupported cave module defaults");
            for (boolean absentRange : List.of(false, true)) {
                double min = Double.parseDouble(rangeDefault("min", absentRange)), max = Double.parseDouble(rangeDefault("max", absentRange));
                if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) throw new IOException("Invalid cave module range defaults");
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) {
        String base = ResourceTypes.base(path);
        if (base == null || !CaveProfileFields.CONTEXTS.contains(base)) throw new IllegalArgumentException("Cave modules are read-only");
    }
    private static void authorize(String path, String field) {
        authorize(path);
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported cave module scalar");
    }
    public static int minimumEntries() { return Integer.parseInt(DEFINITIONS.getProperty("minimumEntries")); }
    public static String description() { return DEFINITIONS.getProperty("description", ""); }
    public static String help(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String styleDefault() { return DEFINITIONS.getProperty("style.default"); }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field + ".type")); }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported cave module scalar");
        String token = DEFINITIONS.getProperty(field + ".default");
        return booleanField(field) ? token : new BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String constraints(String field) {
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        return "Finite number" + (min == null ? "" : ", minimum " + min) + (max == null ? "" : ", maximum " + max);
    }
    public static String draftPrefix(String context) {
        CavePlacementFields.draftPrefix(context);
        return "caveModules#" + context + ".";
    }
    public static String draftPrefix(String context, int index) {
        if (index < 0) throw new IllegalArgumentException("Invalid cave module index");
        return draftPrefix(context) + index + ".";
    }
    static List<LosslessJson> entries(LosslessJson profile) {
        return profile.token("modules") == null ? List.of() : profile.objectArray("modules");
    }
    public static byte[] change(String path, byte[] original, Action action, int index) {
        authorize(path);
        if (action == null) throw new IllegalArgumentException("Choose a module list action");
        return new LosslessJson(original).objectEdit("caveProfile", profile -> {
            if (profile.token("modules") == null) {
                if (action != Action.ADD) throw new IllegalArgumentException("Cave module list is absent");
                profile = new LosslessJson(profile.enable("modules", "[]"));
            }
            var entries = profile.objectArray("modules");
            if (action == Action.ADD) return profile.objectArrayInsert("modules", entries.size(), "{}".getBytes(StandardCharsets.UTF_8));
            if (index < 0 || index >= entries.size()) throw new IllegalArgumentException("Cave module index out of range");
            return switch (action) {
                case DUPLICATE -> profile.objectArrayInsert("modules", index + 1, entries.get(index).sourceBytes());
                case REMOVE -> {
                    if (entries.size() <= minimumEntries()) throw new IllegalArgumentException("Keep at least " + minimumEntries() + " cave module entry");
                    yield profile.objectArrayRemove("modules", index);
                }
                case UP -> profile.objectArrayMove("modules", index, index - 1);
                case DOWN -> profile.objectArrayMove("modules", index, index + 1);
                default -> throw new IllegalArgumentException("Unsupported cave module action");
            };
        }, false);
    }
    static byte[] editObject(byte[] original, int index, Function<LosslessJson, byte[]> operation) {
        return new LosslessJson(original).objectEdit("caveProfile", profile -> profile.objectArrayEdit("modules", index, operation), false);
    }
    public static byte[] enableScalar(String path, byte[] original, int index, String field) {
        authorize(path, field);
        return editObject(original, index, module -> module.enable(field, defaultToken(field)));
    }
    public static byte[] editBoolean(String path, byte[] original, int index, String field, boolean value) {
        authorize(path, field);
        if (!booleanField(field)) throw new IllegalArgumentException("Numeric module field requires a number");
        return editObject(original, index, module -> module.token(field) == null ? module.enable(field, Boolean.toString(value)) : module.bool(field, value));
    }
    public static byte[] editText(String path, byte[] original, int index, String field, String input) {
        authorize(path, field);
        if (booleanField(field)) throw new IllegalArgumentException("Boolean module field requires its toggle");
        double value = Double.parseDouble(input);
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        if (!Double.isFinite(value) || min != null && value < Double.parseDouble(min) || max != null && value > Double.parseDouble(max))
            throw new IllegalArgumentException("Value violates pinned cave module bounds");
        return editObject(original, index, module -> module.number(field, value));
    }
    public static String rangeDefault(String member, boolean absentRange) {
        if (!CavePlacementFields.RANGE_MEMBERS.contains(member)) throw new IllegalArgumentException("Unsupported module range bound");
        return new BigDecimal(DEFINITIONS.getProperty((absentRange ? "verticalRange." : "range.") + member + ".default")).stripTrailingZeros().toPlainString();
    }
    static LosslessJson range(LosslessJson module) {
        String token = module.token("verticalRange");
        return token == null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] enableRange(String path, byte[] original, int index) {
        authorize(path);
        return editObject(original, index, module -> {
            if (module.token("verticalRange") != null) throw new IllegalArgumentException("Module range is already present or unsupported");
            return module.objectEdit("verticalRange", range -> {
                byte[] min = range.enable("min", rangeDefault("min", true));
                return new LosslessJson(min).enable("max", rangeDefault("max", true));
            }, true);
        });
    }
    public static byte[] editRange(String path, byte[] original, int index, String minimum, String maximum) {
        authorize(path);
        double min = Double.parseDouble(minimum), max = Double.parseDouble(maximum);
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) throw new IllegalArgumentException("Enter finite module bounds with minimum at most maximum");
        return editObject(original, index, module -> module.objectEdit("verticalRange", range -> {
            byte[] next = CavePlacementFields.bound(range, "min", min);
            return CavePlacementFields.bound(new LosslessJson(next), "max", max);
        }, false));
    }
    static List<String> readOnlyPaths(LosslessJson profile) {
        var paths = new ArrayList<String>();
        try {
            var entries = entries(profile);
            for (int index = 0; index < entries.size(); index++) {
                var module = entries.get(index); String prefix = "modules[" + index + "].";
                for (String field : module.fields()) {
                    if (EDITABLE.contains(field)) continue;
                    if (field.equals("style") || field.equals("verticalRange")) {
                        try {
                            var object = new LosslessJson(module.token(field).getBytes(StandardCharsets.UTF_8));
                            if (field.equals("style")) { for (String member : StyleFractures.readOnlyPaths(object)) paths.add(prefix + field + "." + member); }
                            else for (String member : object.fields()) if (!CavePlacementFields.RANGE_MEMBERS.contains(member)) paths.add(prefix + field + "." + member);
                        } catch (IllegalArgumentException mismatch) { paths.add(prefix + field); }
                    } else paths.add(prefix + field);
                }
            }
        } catch (IllegalArgumentException mismatch) { paths.add("modules"); }
        return List.copyOf(paths);
    }
}
