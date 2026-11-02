package dev.irisvisualizer.editor;

import java.math.BigDecimal;
import java.util.OptionalDouble;
import java.util.Properties;

/** Presentation only: existing typed setters remain the authority for edits. */
public record NumericBounds(boolean integer, double minimum, double maximum, boolean slider) {
    public static final NumericBounds TEXT = new NumericBounds(false, 0, 0, false);
    public static final double MAXIMUM_SLIDER_SPAN = 1024;

    public static NumericBounds from(Properties definitions, String field) {
        return of(definitions.getProperty(field + ".type"), definitions.getProperty(field + ".minimum"),
                definitions.getProperty(field + ".maximum"));
    }

    public static NumericBounds of(String type, String low, String high) {
        boolean integer = "int".equals(type) || "Integer".equals(type);
        // Seeds/time stay exact text fields, even if a future source adds narrow long bounds.
        if (!integer && !"double".equals(type) && !"Double".equals(type)) return TEXT;
        if (low == null || high == null) return TEXT;
        try {
            double minimum = Double.parseDouble(low), maximum = Double.parseDouble(high);
            double span = maximum - minimum;
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || !Double.isFinite(span)
                    || span < 1e-9 || span > MAXIMUM_SLIDER_SPAN
                    || Math.abs(minimum) > 1e9 || Math.abs(maximum) > 1e9) return TEXT;
            if (integer) {
                new BigDecimal(low).intValueExact();
                new BigDecimal(high).intValueExact();
            }
            return new NumericBounds(integer, minimum, maximum, true);
        } catch (NumberFormatException | ArithmeticException invalid) {
            return TEXT;
        }
    }

    /** Invalid, inherited/empty and out-of-range drafts must never be clamped merely by drawing. */
    public OptionalDouble draft(String token) {
        if (!slider || !LosslessJson.numericToken(token)) return OptionalDouble.empty();
        try {
            double value = integer ? new BigDecimal(token).intValueExact() : Double.parseDouble(token);
            return Double.isFinite(value) && value >= minimum && value <= maximum
                    ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException | ArithmeticException invalid) {
            return OptionalDouble.empty();
        }
    }
}
