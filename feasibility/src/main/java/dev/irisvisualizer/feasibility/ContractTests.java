package dev.irisvisualizer.feasibility;

import dev.irisvisualizer.engine.PreviewReadiness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Dependency-free contract checks; executed only in the verification phase. */
public final class ContractTests {
    private static int checks;
    private ContractTests() {}
    public static void main(String[] args) throws Exception {
        PreviewBudget budget = PreviewBudget.PROPOSED;
        equal(50_331_648L, budget.volumeBytes(128,128,-64,320));
        budget.requireVolume(128,128,-64,320);
        rejects(() -> budget.requireVolume(4096,4096,-64,320));
        rejects(() -> budget.volumeBytes(128,128,320,-64));
        rejects(() -> budget.volumeBytes(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE));
        rejects(() -> new PreviewBudget(0,1));

        Path root = Files.createTempDirectory("iris-fingerprint-test-");
        try {
            Files.createDirectories(root.resolve("biomes"));
            Files.writeString(root.resolve("biomes/a.json"), "{\"unknown\":true}\r\n");
            String first = PackFingerprint.sha256(root);
            Files.createDirectories(root.resolve(".git"));
            Files.writeString(root.resolve(".git/config"), "checkout metadata");
            equal(first, PackFingerprint.sha256(root));
            Files.writeString(root.resolve("biomes/a.json"), "{\"unknown\":true}\n");
            different(first, PackFingerprint.sha256(root));
            String changed = PackFingerprint.sha256(root);
            Files.move(root.resolve("biomes/a.json"), root.resolve("biomes/b.json"));
            different(changed, PackFingerprint.sha256(root));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }

        String header = "seed\tchunkX\tchunkZ\tminY\tmaxYExclusive\tblocksSha256\tbiomesSha256";
        String row = "1337\t-1\t0\t-64\t320\t" + "a".repeat(64) + "\t" + "b".repeat(64);
        List<String> sample = List.of(header, row);
        BaselineComparison.compareRows(sample, sample);
        rejects(() -> BaselineComparison.compareRows(sample, List.of(header)));
        rejects(() -> BaselineComparison.compareRows(sample, List.of(header,row,row)));
        rejects(() -> BaselineComparison.compareRows(sample, List.of(header,row.replace("a", "c"))));
        rejects(() -> BaselineComparison.compareRows(sample, List.of(header,row.replace("-1", "1"))));
        rejects(() -> BaselineComparison.compareRows(List.of(header,row.replace("320", "-65")), sample));
        List<String> multiple = List.of(header,row,row.replace("1337", "42"));
        BaselineComparison.compareRows(multiple, List.of(header,multiple.get(2),multiple.get(1)));

        Properties candidate = new Properties(), baseline = new Properties();
        for (String field : List.of("irisRevision","volmlibRevision","volmlibCompatibilitySha256","packRevision","packSha256","dimension","minY","maxYExclusive")) {
            candidate.setProperty(field, "fixture-" + field);
            baseline.setProperty(field, "fixture-" + field);
        }
        candidate.setProperty("status", "CORE_STUB_REPEATABILITY_PASSED");
        baseline.setProperty("backend", "native-iris");
        baseline.setProperty("complete", "true");
        baseline.setProperty("captureProvenance", "test fixture only");
        BaselineComparison.compareMetadata(candidate, baseline);
        baseline.remove("volmlibCompatibilitySha256");
        rejects(() -> BaselineComparison.compareMetadata(candidate, baseline));
        baseline.setProperty("volmlibCompatibilitySha256", "different-helper");
        rejects(() -> BaselineComparison.compareMetadata(candidate, baseline));
        baseline.setProperty("volmlibCompatibilitySha256", candidate.getProperty("volmlibCompatibilitySha256"));
        baseline.setProperty("backend", "official-iris-core-with-upstream-stub-platform");
        rejects(() -> BaselineComparison.compareMetadata(candidate, baseline));
        baseline.setProperty("backend", "native-iris");
        baseline.setProperty("packSha256", "other-pack");
        rejects(() -> BaselineComparison.compareMetadata(candidate, baseline));
        var nativePlacement = new PreviewReadiness.NativePlacement("dimensions", "overworld",
                "/structures/0/nativeStructures/0/structure", "minecraft:ancient_city");
        var blocked = PreviewReadiness.evaluateWorld("overworld", new PreviewReadiness.Capabilities(false, false, false), List.of(nativePlacement));
        equal(false, blocked.previewAllowed());
        equal(4, blocked.blockers().size());
        equal(nativePlacement.propertyPath(), blocked.blockers().stream()
                .filter(issue -> issue.code() == PreviewReadiness.Code.NATIVE_PLACEMENT_INCOMPLETE).findFirst().orElseThrow().propertyPath());
        equal(3, PreviewReadiness.evaluateWorld("overworld", new PreviewReadiness.Capabilities(false, false, false), List.of()).blockers().size());
        equal(List.of(PreviewReadiness.Code.NATIVE_BASELINE_MISSING), PreviewReadiness.evaluateWorld("overworld",
                new PreviewReadiness.Capabilities(true, false, true), List.of(nativePlacement)).blockers().stream().map(PreviewReadiness.Issue::code).toList());
        equal(List.of(PreviewReadiness.Code.MEMORY_BUDGET_UNCONFIRMED), PreviewReadiness.evaluateWorld("overworld",
                new PreviewReadiness.Capabilities(true, true, false), List.of(nativePlacement)).blockers().stream().map(PreviewReadiness.Issue::code).toList());
        equal(true, PreviewReadiness.evaluateWorld("overworld", new PreviewReadiness.Capabilities(true, true, true), List.of(nativePlacement)).previewAllowed());
        System.out.println("PASS: " + checks + " contract assertions (no native generation or parity claim)");
    }
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", received " + actual);
    }
    private static void different(Object expected, Object actual) {
        checks++;
        if (expected.equals(actual)) throw new AssertionError("Expected fingerprint to change");
    }
    private static void rejects(Runnable operation) {
        checks++;
        try { operation.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("Invalid input was accepted");
    }
}
