package art.arcane.iris.probe;

import dev.irisvisualizer.feasibility.PackFingerprint;
import dev.irisvisualizer.feasibility.PreviewBudget;
import java.io.BufferedWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Calls the pinned official engine. No terrain/noise algorithms are implemented here. */
public final class VisualizerFeasibility {
    private VisualizerFeasibility() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected: pack dimension output-directory");
        requireWindowsJava25();
        Path pack = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output);
        Properties report = new Properties();
        report.setProperty("status", "FAILED");
        report.setProperty("nativeParity", "NOT_TESTED");
        report.setProperty("backend", "official-iris-core-with-upstream-stub-platform");
        report.setProperty("irisRevision", requiredEnvironment("IRIS_VISUALIZER_IRIS_REVISION"));
        report.setProperty("volmlibRevision", requiredEnvironment("IRIS_VISUALIZER_VOLMLIB_REVISION"));
        report.setProperty("packRevision", requiredEnvironment("IRIS_VISUALIZER_PACK_REVISION"));
        report.setProperty("javaRuntime", System.getProperty("java.runtime.version"));
        report.setProperty("dimension", args[1]);
        report.setProperty("defaultArea", "128x128");
        report.setProperty("defaultMap", "512x512");
        report.setProperty("generationCacheProposalBytes", Long.toString(PreviewBudget.PROPOSED.generationBytes()));
        report.setProperty("meshCacheProposalBytes", Long.toString(PreviewBudget.PROPOSED.meshBytes()));
        report.setProperty("cacheBudgetsConfirmed", "false");
        report.setProperty("limitations", "Stub block solidity, occlusion, registries and decoration are approximations; native features are not completed. No vanilla parity claim.");
        String before = PackFingerprint.sha256(pack);
        report.setProperty("packSha256", before);
        long started = System.nanoTime();
        PrintStream originalOutput = System.out;
        InitializationDiagnostics diagnostics = new InitializationDiagnostics(originalOutput);
        System.setOut(diagnostics);
        try {
            // The official helper creates an isolated disposable generation workspace, not recovery/history files.
            // Its platform implementation and generation semantics remain unmodified.
            try (RealPackProbeSupport.Workspace workspace = RealPackProbeSupport.openWorkspace(
                    pack.toFile(), args[1], "[visualizer-feasibility]")) {
                for (long seed : new long[]{1337L, -8675309L}) {
                    List<String> first = run(workspace, seed, output.resolve(seed + "-first.tsv"), report, diagnostics);
                    List<String> second = run(workspace, seed, output.resolve(seed + "-repeat.tsv"), report, diagnostics);
                    if (!first.equals(second)) throw new IllegalStateException("Generation changed between fresh sessions at seed " + seed);
                }
            }
            report.setProperty("status", "CORE_STUB_REPEATABILITY_PASSED");
        } catch (Exception | Error failure) {
            report.setProperty("failure", failure.getClass().getName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            System.setOut(originalOutput);
            try {
                String after = PackFingerprint.sha256(pack);
                report.setProperty("sourcePackUnchanged", Boolean.toString(before.equals(after)));
                if (!before.equals(after)) {
                    report.setProperty("status", "FAILED");
                    throw new IllegalStateException("Probe changed the source pack");
                }
            } finally {
                report.setProperty("elapsedMillis", Long.toString((System.nanoTime() - started) / 1_000_000));
                report.setProperty("peakHeapBytes", Long.toString(peakHeapBytes()));
                try (var writer = Files.newBufferedWriter(output.resolve("report.properties"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW)) { report.store(writer, "Iris Visualizer feasibility; not a native parity baseline"); }
            }
        }
    }

    private static List<String> run(RealPackProbeSupport.Workspace workspace, long seed, Path output, Properties report,
                                    InitializationDiagnostics diagnostics) throws Exception {
        var rows = new java.util.ArrayList<String>();
        try (RealPackProbeSupport.EngineSession session = workspace.openEngine(seed, false, "visualizer")) {
            // The upstream stub helper logs and drains initialization failures. Fail the probe when it reports any.
            if (diagnostics.reportedFailure) throw new IllegalStateException("Official core reported initialization failures; see console diagnostics");
            var engine = session.engine();
            int minY = engine.getMinHeight(), maxY = engine.getMaxHeight();
            if (maxY - (long) minY != engine.getTarget().getHeight()) throw new IllegalStateException("Height contract mismatch");
            PreviewBudget.PROPOSED.requireVolume(128, 128, minY, maxY);
            report.setProperty("minY", Integer.toString(minY));
            report.setProperty("maxYExclusive", Integer.toString(maxY));
            // Two representative four-chunk windows including negative and distant coordinates.
            // This smoke sample does not change the application's required 128x128 default.
            int[][] coordinates = {{0,0},{1,0},{0,1},{1,1},{-17,31},{-16,31},{-17,32},{-16,32}};
            for (int[] coordinate : coordinates) {
                var chunk = RealPackProbeSupport.generateChunk(engine, coordinate[0], coordinate[1]);
                var errors = RealPackProbeSupport.settleAndDrain(engine);
                if (!errors.isEmpty()) {
                    var failure = new IllegalStateException("Official core reported generation errors at " + coordinate[0] + "," + coordinate[1]);
                    errors.forEach(failure::addSuppressed);
                    throw failure;
                }
                MessageDigest blocks = PackFingerprint.digest(), biomes = PackFingerprint.digest();
                int nonAir = 0;
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < chunk.height(); y++) {
                    var block = chunk.blocks().get(x,y,z);
                    var biome = chunk.biomes().get(x,y,z);
                    // Nulls remain explicit. Do not substitute a vanilla block or biome.
                    update(blocks, block == null ? "<null>" : block.key());
                    update(biomes, biome == null ? "<null>" : biome.key());
                    if (block != null && !block.isAir()) nonAir++;
                }
                if (nonAir == 0) throw new IllegalStateException("Representative chunk contains no solid terrain");
                String row = seed + "\t" + coordinate[0] + "\t" + coordinate[1] + "\t" + minY + "\t" + maxY
                        + "\t" + HexFormat.of().formatHex(blocks.digest()) + "\t" + HexFormat.of().formatHex(biomes.digest());
                rows.add(row);
                System.out.println("[visualizer-feasibility] " + row);
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("seed\tchunkX\tchunkZ\tminY\tmaxYExclusive\tblocksSha256\tbiomesSha256\n");
            for (String row : rows) { writer.write(row); writer.newLine(); }
        }
        return List.copyOf(rows);
    }
    private static void update(MessageDigest digest, String key) {
        digest.update(key.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
    private static final class InitializationDiagnostics extends PrintStream {
        private volatile boolean reportedFailure;
        InitializationDiagnostics(PrintStream destination) { super(destination, true, StandardCharsets.UTF_8); }
        @Override public void println(String value) {
            if (value != null && value.startsWith("[visualizer-feasibility]") && value.contains("engine-init reports"))
                reportedFailure = true;
            super.println(value);
        }
    }
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || !value.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Missing pinned " + name);
        return value;
    }
    private static long peakHeapBytes() {
        return java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == java.lang.management.MemoryType.HEAP)
                .mapToLong(p -> p.getPeakUsage().getUsed()).sum();
    }
    private static void requireWindowsJava25() {
        if (!System.getProperty("os.name").startsWith("Windows") || !List.of("amd64","x86_64").contains(System.getProperty("os.arch"))
                || Runtime.version().feature() != 25) throw new IllegalStateException("Windows x64 and Java 25 are required");
    }
}
