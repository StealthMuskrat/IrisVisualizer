package dev.irisvisualizer.editor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Ordered numeric options from pinned contracts; indices never approximate the numeric values. */
public final class NumericChoices {
    public static final NumericChoices EMPTY = new NumericChoices(List.of(), List.of());
    private final List<String> tokens;
    private final List<BigDecimal> numbers;

    private NumericChoices(List<String> tokens, List<BigDecimal> numbers) {
        this.tokens = List.copyOf(tokens);
        this.numbers = List.copyOf(numbers);
    }

    public static NumericChoices parse(String source) {
        if (source == null || source.isBlank() || source.length() > 8192) return EMPTY;
        String[] rows = source.split(",", -1);
        if (rows.length < 2 || rows.length > 64) return EMPTY;
        var tokens = new ArrayList<String>();
        var numbers = new ArrayList<BigDecimal>();
        try {
            for (String row : rows) {
                String token = row.trim();
                if (token.length() > 128 || !LosslessJson.numericToken(token)) return EMPTY;
                var number = new BigDecimal(token);
                if (numbers.stream().anyMatch(previous -> previous.compareTo(number) == 0)) return EMPTY;
                tokens.add(token); numbers.add(number);
            }
            return new NumericChoices(tokens, numbers);
        } catch (NumberFormatException | ArithmeticException invalid) {
            return EMPTY;
        }
    }

    public List<String> tokens() { return tokens; }

    /** Equivalent valid spellings select the same option without changing the pending token. */
    public int index(String draft) {
        if (tokens.isEmpty() || draft == null || draft.length() > 4096 || !LosslessJson.numericToken(draft)) return -1;
        try {
            var number = new BigDecimal(draft);
            for (int i = 0; i < numbers.size(); i++) if (numbers.get(i).compareTo(number) == 0) return i;
        } catch (NumberFormatException | ArithmeticException invalid) {
            // An incomplete or malformed draft remains available in exact text entry.
        }
        return -1;
    }
}
