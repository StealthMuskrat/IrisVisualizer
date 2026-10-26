package dev.irisvisualizer.feasibility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** A native baseline must be captured separately. Repeatability is never labeled native parity. */
public final class BaselineComparison {
    private static final String HEADER = "seed\tchunkX\tchunkZ\tminY\tmaxYExclusive\tblocksSha256\tbiomesSha256";
    private BaselineComparison() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("Expected: candidate-report candidate-tsv native-report native-tsv");
        Properties candidate = load(Path.of(args[0])), nativeReport = load(Path.of(args[2]));
        compareMetadata(candidate, nativeReport);
        compareRows(Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8), Files.readAllLines(Path.of(args[3]), StandardCharsets.UTF_8));
        System.out.println("MATCH: block and biome hashes agree for the supplied coordinates and pinned content only.");
    }

    static void compareMetadata(Properties candidate, Properties baseline) {
        if (!"native-iris".equals(baseline.getProperty("backend")) || !"true".equals(baseline.getProperty("complete")))
            throw new IllegalArgumentException("Baseline must be an independently captured complete native Iris result");
        String provenance = baseline.getProperty("captureProvenance", "");
        if (provenance.isBlank()) throw new IllegalArgumentException("Native capture provenance is required");
        if (!"CORE_STUB_REPEATABILITY_PASSED".equals(candidate.getProperty("status")))
            throw new IllegalArgumentException("Candidate probe did not pass repeatability");
        for (String field : List.of("irisRevision", "volmlibRevision", "volmlibCompatibilitySha256", "packRevision", "packSha256", "dimension", "minY", "maxYExclusive")) {
            String value = candidate.getProperty(field);
            if (value == null || value.isBlank() || !value.equals(baseline.getProperty(field)))
                throw new IllegalArgumentException("Baseline context mismatch: " + field);
        }
    }

    static void compareRows(List<String> candidate, List<String> baseline) {
        Map<String, String> actual = rows(candidate), expected = rows(baseline);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Block/biome hashes or sampled coordinates differ from native baseline");
    }

    private static Map<String, String> rows(List<String> lines) {
        if (lines.size() < 2 || !HEADER.equals(lines.getFirst())) throw new IllegalArgumentException("Missing sample rows or invalid hash format");
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 7 || !fields[5].matches("[0-9a-f]{64}") || !fields[6].matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Malformed sample row");
            Long.parseLong(fields[0]);
            int x = Integer.parseInt(fields[1]), z = Integer.parseInt(fields[2]);
            if (x < Integer.MIN_VALUE / 16 || x > Integer.MAX_VALUE / 16 || z < Integer.MIN_VALUE / 16 || z > Integer.MAX_VALUE / 16)
                throw new IllegalArgumentException("Chunk is outside world-coordinate integer range");
            if (Integer.parseInt(fields[4]) <= Integer.parseInt(fields[3])) throw new IllegalArgumentException("Invalid sample height");
            String key = String.join("\t", java.util.Arrays.copyOf(fields, 5));
            if (values.putIfAbsent(key, fields[5] + "\t" + fields[6]) != null) throw new IllegalArgumentException("Duplicate sample coordinate");
        }
        return values;
    }
    private static Properties load(Path path) throws IOException {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { values.load(reader); }
        return values;
    }
}
