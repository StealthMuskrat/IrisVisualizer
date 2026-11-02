package dev.irisvisualizer.editor;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Deferred end-of-UI contracts for discrete choices without numeric approximation. */
final class NumericChoicesTests {
    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }

    static void run() {
        var choices = NumericChoices.parse("1,2,4,8");
        check(choices.tokens().equals(java.util.List.of("1", "2", "4", "8")), "source option order remains exact");
        for (String token : new String[]{"2", "2.0", "2e0", "20e-1"})
            check(choices.index(token) == 1, "equivalent numeric spellings select the same option: " + token);
        for (String token : new String[]{null, "", "null", "true", "\"2\"", "02", "2.", "3", "2.0000000000000001", "1e999999999999"})
            check(choices.index(token) == -1, "unknown/incomplete drafts never choose an approximate value: " + token);
        var exact = NumericChoices.parse("9007199254740992,9007199254740993,9223372036854775807");
        check(exact.index("9007199254740992") == 0 && exact.index("9007199254740993") == 1
                && exact.index("9223372036854775807") == 2, "indices distinguish exact integers above double precision");
        check(NumericChoices.parse("8,1,4").tokens().getFirst().equals("8"), "source ordering is not sorted numerically");
        for (String source : new String[]{null, "", "1", "1,,2", "1,2,", "01,2", "1,NaN", "1,Infinity", "1,1.0", "0,-0", "1e0,1", "1," + "9".repeat(129), "9".repeat(8193)})
            check(NumericChoices.parse(source).tokens().isEmpty(), "invalid, duplicate and oversized option sets fail closed");
        String maximum = IntStream.range(0, 64).mapToObj(Integer::toString).collect(Collectors.joining(","));
        check(NumericChoices.parse(maximum).tokens().size() == 64, "the option budget is inclusive");
        check(NumericChoices.parse(maximum + ",64").tokens().isEmpty(), "an excessive option set stays a text field");
        check(choices.index("2".repeat(4097)) == -1, "oversized drafts remain unselected");
        try { choices.tokens().add("16"); throw new AssertionError("options must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        check(DimensionFields.numericChoices("terrainSamplingStep").tokens().equals(choices.tokens()), "dimension sampling uses the pinned option set");
        check(DimensionFields.numericChoices("landChance").tokens().isEmpty(), "continuous fields do not gain discrete options");
        System.out.println("PASS: discrete numeric source options, exact large integers, equivalent spellings, malformed/incomplete drafts, immutable ordered choices and bounded option parsing");
    }
}
