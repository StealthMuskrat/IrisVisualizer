package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Audited UI policy: these two dimension fields alter terrain; all other fields remain read-only. */
public final class DimensionFields {
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = DimensionFields.class.getResourceAsStream("/dimension-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned field definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static final List<String> EDITABLE = List.of("fluidHeight", "dimensionAngleDeg");
    public static boolean dimension(String path) { return path.startsWith("dimensions/") && path.substring(11).indexOf('/') < 0 && path.endsWith(".json"); }
    public static double min(String field) { return Double.parseDouble(DEFINITIONS.getProperty(field + ".minimum")); }
    public static double max(String field) { return Double.parseDouble(DEFINITIONS.getProperty(field + ".maximum")); }
    public static byte[] edit(String path, byte[] original, String field, double value) {
        if (!dimension(path) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Field is read-only");
        if (!Double.isFinite(value) || value < min(field) || value > max(field)
                || (DEFINITIONS.getProperty(field + ".type").equals("int") && value != Math.rint(value)))
            throw new IllegalArgumentException("Value violates the pinned Iris field constraints");
        return new LosslessJson(original).number(field, value);
    }
}
