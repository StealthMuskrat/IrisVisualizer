package art.arcane.iris.probe;

import art.arcane.iris.generation.runtime.EnginePlatformHooks;
import art.arcane.iris.generation.runtime.EngineTarget;
import art.arcane.iris.generation.runtime.IrisEngine;
import art.arcane.iris.pack.PackValidator;
import art.arcane.iris.pack.datapack.v263.DataFixerV263;
import art.arcane.iris.pack.loading.IrisData;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.world.IrisWorld;
import art.arcane.iris.world.history.GenerationHistoryPaths;
import art.arcane.iris.world.history.GenerationPackFingerprint;
import art.arcane.iris.world.history.GenerationRegistryContractFactory;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBiome;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeBlockResolver;
import dev.irisvisualizer.feasibility.PackFingerprint;
import dev.irisvisualizer.feasibility.PreviewBudget;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Development proof for one pinned official fixture; not a general-purpose pack backend. */
public final class NativeFixtureProof {
    private static final int AREA = PreviewBudget.DEFAULT_AREA;
    private static final String DIMENSION = "terrain3d";
    private NativeFixtureProof() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected: official-fixture output-directory seed");
        if (!System.getProperty("os.name").startsWith("Windows") || Runtime.version().feature() != 25
                || !List.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw new IllegalStateException("Windows x64 and Java 25 are required");
        Path source = Path.of(args[0]).toRealPath(), output = Path.of(args[1]).toAbsolutePath();
        long seed = Long.parseLong(args[2]);
        String before = PackFingerprint.sha256(source);
        if (!before.equals(required("IRIS_VISUALIZER_NATIVE_FIXTURE_SHA256")))
            throw new IllegalArgumentException("This development proof accepts only the exact pinned official terrain3d fixture");
        Files.createDirectories(output);
        Properties report = new Properties();
        report.setProperty("status", "FAILED");
        report.setProperty("backend", "official-iris-core-with-native-vanilla-registries");
        report.setProperty("scope", "pinned-terrain3d-fixture-only");
        report.setProperty("nativeParity", "NOT_TESTED");
        report.setProperty("completeNativeWorld", "false");
        report.setProperty("cacheBudgetsConfirmed", "false");
        report.setProperty("irisVersion", required("IRIS_VISUALIZER_IRIS_VERSION"));
        report.setProperty("irisRevision", required("IRIS_VISUALIZER_IRIS_REVISION"));
        report.setProperty("volmlibRevision", required("IRIS_VISUALIZER_VOLMLIB_REVISION"));
        report.setProperty("volmlibCompatibilitySha256", required("IRIS_VISUALIZER_VOLMLIB_COMPATIBILITY_SHA256"));
        report.setProperty("volmlibTemplateServicesSha256", required("IRIS_VISUALIZER_VOLMLIB_TEMPLATE_SERVICES_SHA256"));
        report.setProperty("nativePieceAdapterSha256", required("IRIS_VISUALIZER_NATIVE_PIECE_ADAPTER_SHA256"));
        report.setProperty("nativeStructureStageAdapterSha256", required("IRIS_VISUALIZER_NATIVE_STRUCTURE_STAGE_SHA256"));
        report.setProperty("packRevision", required("IRIS_VISUALIZER_IRIS_REVISION"));
        report.setProperty("packSource", "VolmitSoftware/Iris:probe/src/test/resources/terrain3d-pack");
        report.setProperty("packSha256", before);
        report.setProperty("dimension", DIMENSION);
        report.setProperty("seed", Long.toString(seed));
        report.setProperty("javaRuntime", System.getProperty("java.runtime.version"));
        report.setProperty("limitations", "Ordinary core terrain fixture only. Native structures, placed-feature completion and production Overworld parity are not covered. No history workflow is used.");
        Path scratch = Files.createTempDirectory("iris-visualizer-native-fixture-");
        long started = System.nanoTime();
        try {
            Path copy = scratch.resolve("pack");
            copyFixture(source, copy);
            if (!before.equals(PackFingerprint.sha256(copy))) throw new IllegalStateException("Fixture copy changed bytes");
            generate(copy, scratch, output, seed, report);
            ProbeWorkspaceCleanup.deleteRecursively(scratch);
            if (Files.exists(scratch)) throw new IllegalStateException("Native scratch workspace was not deleted");
            requireNoServer();
            report.setProperty("workspaceDeleted", "true");
            report.setProperty("status", "NATIVE_FIXTURE_PASSED");
        } catch (Exception | Error failure) {
            report.setProperty("failure", failure.getClass().getName() + ": " + failure.getMessage());
            try { ProbeWorkspaceCleanup.deleteRecursively(scratch); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            try {
                boolean unchanged = before.equals(PackFingerprint.sha256(source));
                report.setProperty("sourcePackUnchanged", Boolean.toString(unchanged));
                if (!unchanged) {
                    report.setProperty("status", "FAILED");
                    throw new IllegalStateException("Native proof changed the source fixture");
                }
            } finally {
                report.setProperty("elapsedMillis", Long.toString((System.nanoTime() - started) / 1_000_000));
                report.setProperty("heapPoolPeakBytesSum", Long.toString(ManagementFactory.getMemoryPoolMXBeans().stream()
                        .filter(pool -> pool.getType() == MemoryType.HEAP).mapToLong(pool -> pool.getPeakUsage().getUsed()).sum()));
                report.setProperty("maxJvmHeapBytes", Long.toString(Runtime.getRuntime().maxMemory()));
                try (var writer = Files.newBufferedWriter(output.resolve("report.properties"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                    report.store(writer, "Native fixture feasibility, not a complete native-world parity result");
                }
            }
        }
        System.out.println("NATIVE_FIXTURE_PASSED seed=" + seed + " area=128x128 sourcePackUnchanged=true workspaceDeleted=true nativeParity=NOT_TESTED");
    }

    private static void generate(Path pack, Path scratch, Path output, long seed, Properties report) throws Exception {
        requireNoServer();
        // The prepared helper only installs excluded-gameplay services; it does not parse the pack or open an engine.
        try (var workspace = RealPackProbeSupport.openPreparedWorkspace(pack.toFile(), DIMENSION, "[native-fixture]", false);
             var runtime = new HeadlessNativeRuntime()) {
            IrisPlatforms.unbind();
            var platform = runtime.create(scratch.resolve("platform").toFile());
            IrisPlatforms.bind(platform);
            IrisServices.register(EnginePlatformHooks.class, runtime);
            report.setProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
            if (!required("IRIS_VISUALIZER_MINECRAFT_VERSION").equals(SharedConstants.getCurrentVersion().name()))
                throw new IllegalStateException("Native Minecraft runtime does not match the pinned asset target");
            report.setProperty("nativeStatesChecked", Integer.toString(checkEveryVanillaState()));
            IrisData data = IrisData.openRuntime(pack.toFile());
            try (AutoCloseable closeData = data::close) {
                var dimension = data.getDimensionLoader().load(DIMENSION);
                if (dimension == null) throw new IllegalStateException("Official terrain3d dimension did not load");
                runtime.prepare(data, dimension);
                var validation = PackValidator.validateForDatapackBootstrap(pack.toFile());
                if (!validation.isLoadable()) throw new IllegalStateException("Fixture validation failed: " + validation.getBlockingErrors());
                String fingerprint = GenerationPackFingerprint.compute(pack, GenerationPackFingerprint.CURRENT_VERSION);
                // This factory creates an in-memory registry contract, not epochs, snapshots or recovery files.
                data.bindGenerationRegistryContract(GenerationRegistryContractFactory.create(data, dimension, fingerprint,
                        new DataFixerV263(), platform.registries(), platform.registries().generationRegistry()));
                Path world = scratch.resolve("world");
                IrisWorld targetWorld = IrisWorld.builder().platformIdentity("iris:visualizer-native-proof").name("native-proof")
                        .seed(seed).worldFolder(world.toFile()).minHeight(dimension.getMinHeight()).maxHeight(dimension.getMaxHeight()).build();
                var engine = new IrisEngine(new EngineTarget(targetWorld, dimension, data), IrisEngine.InitializationMode.RUNTIME);
                try (AutoCloseable closeEngine = engine::close) {
                    requireNoReports(engine);
                    captureVolume(engine, output, seed, report);
                }
                requireNoReports(null);
                if (Files.exists(GenerationHistoryPaths.forDimension(world).generationRoot()))
                    throw new IllegalStateException("Native proof unexpectedly created a history directory");
                report.setProperty("historyDirectoryAbsent", "true");
            }
        }
        requireNoReports(null);
        requireNoServer();
    }

    private static void captureVolume(IrisEngine engine, Path output, long seed, Properties report) throws Exception {
        int minY = engine.getMinHeight(), maxY = engine.getMaxHeight(), height = maxY - minY;
        PreviewBudget.PROPOSED.requireVolume(AREA, AREA, minY, maxY);
        int cells = Math.toIntExact(Math.multiplyExact(Math.multiplyExact((long) AREA, AREA), height));
        int[] blocks = new int[cells], biomes = new int[cells];
        Map<String, Integer> blockPalette = new LinkedHashMap<>(), biomePalette = new LinkedHashMap<>();
        int nativeBlocks = 0, nativeBiomes = 0, nonAir = 0;
        report.setProperty("width", Integer.toString(AREA));
        report.setProperty("depth", Integer.toString(AREA));
        report.setProperty("minY", Integer.toString(minY));
        report.setProperty("maxYExclusive", Integer.toString(maxY));
        report.setProperty("indexArrayBytes", Long.toString(PreviewBudget.PROPOSED.volumeBytes(AREA, AREA, minY, maxY)));
        report.setProperty("generationCacheProposalBytes", Long.toString(PreviewBudget.PROPOSED.generationBytes()));
        report.setProperty("mapDefaultSamples", "512x512");
        report.setProperty("mapGenerationTested", "false");
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("chunks.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("seed\tchunkX\tchunkZ\tminY\tmaxYExclusive\tblocksSha256\tbiomesSha256\n");
            for (int cx = -4; cx < 4; cx++) for (int cz = -4; cz < 4; cz++) {
                var chunk = RealPackProbeSupport.generateChunk(engine, cx, cz);
                requireNoReports(engine);
                MessageDigest blockHash = PackFingerprint.digest(), biomeHash = PackFingerprint.digest();
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < height; y++) {
                    var block = chunk.blocks().get(x, y, z);
                    var biome = chunk.biomes().get(x, y, z);
                    String blockKey = block == null ? "<null>" : block.key(), biomeKey = biome == null ? "<null>" : biome.key();
                    if (block != null) {
                        if (!(block instanceof ModdedBlockState) || !blockKey.startsWith("minecraft:"))
                            throw new IllegalStateException("Non-native/unsupported generated block: " + blockKey);
                        nativeBlocks++;
                        if (!block.isAir()) nonAir++;
                    }
                    if (biome != null) {
                        if (!(biome instanceof ModdedBiome)) throw new IllegalStateException("Non-native generated biome: " + biomeKey);
                        nativeBiomes++;
                    }
                    int index = (((cx + 4) * 16 + x) * AREA + (cz + 4) * 16 + z) * height + y;
                    blocks[index] = paletteIndex(blockPalette, blockKey);
                    biomes[index] = paletteIndex(biomePalette, biomeKey);
                    update(blockHash, blockKey); update(biomeHash, biomeKey);
                }
                writer.write(seed + "\t" + cx + "\t" + cz + "\t" + minY + "\t" + maxY + "\t"
                        + HexFormat.of().formatHex(blockHash.digest()) + "\t" + HexFormat.of().formatHex(biomeHash.digest()));
                writer.newLine(); writer.flush();
                System.out.println("[native-fixture] chunk=" + cx + "," + cz + " complete");
            }
        }
        if (nativeBlocks == 0 || nativeBiomes == 0 || nonAir == 0 || blockPalette.size() < 2)
            throw new IllegalStateException("Native fixture did not produce representative block and biome data");
        // Touch the complete retained index buffers after generation, proving full-volume allocation and population.
        long checksum = 0;
        for (int i = 0; i < cells; i++) checksum += blocks[i] + biomes[i];
        report.setProperty("indexChecksum", Long.toString(checksum));
        report.setProperty("chunks", "64");
        report.setProperty("nativeBlockCells", Integer.toString(nativeBlocks));
        report.setProperty("nativeBiomeCells", Integer.toString(nativeBiomes));
        report.setProperty("blockPaletteSize", Integer.toString(blockPalette.size()));
        report.setProperty("biomePaletteSize", Integer.toString(biomePalette.size()));
    }

    private static int checkEveryVanillaState() {
        int count = 0;
        for (Block block : BuiltInRegistries.BLOCK) for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            var decoded = NativeBlockResolver.strictParse(ModdedBlockState.serialize(state));
            if (!state.equals(decoded.handle())) throw new IllegalStateException("Vanilla state did not round-trip: " + state);
            count++;
        }
        for (String invalid : List.of("unsupported_plugin:missing_ore", "minecraft:oak_log[axis=invalid]", "minecraft:stone[unknown=true]")) {
            try { NativeBlockResolver.strictParse(invalid); }
            catch (IllegalArgumentException expected) { continue; }
            throw new IllegalStateException("Invalid or external state was silently accepted: " + invalid);
        }
        if (count == 0) throw new IllegalStateException("Native block registry is empty");
        System.out.println("[native-fixture] vanilla states checked=" + count);
        return count;
    }
    private static int paletteIndex(Map<String, Integer> palette, String key) {
        Integer index = palette.get(key);
        if (index != null) return index;
        int created = palette.size(); palette.put(key, created); return created;
    }
    private static void update(MessageDigest digest, String key) {
        digest.update(key.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
    static void requireNoReports(IrisEngine engine) throws Exception {
        var errors = engine == null ? RealPackProbeSupport.drainReported() : RealPackProbeSupport.settleAndDrain(engine);
        if (!errors.isEmpty()) {
            var failure = new IllegalStateException("Native fixture reported " + errors.size() + " error(s)");
            errors.forEach(failure::addSuppressed); throw failure;
        }
        requireNoServer();
    }
    static void requireNoServer() {
        if (Bukkit.getServer() != null) throw new IllegalStateException("Native fixture must not run a Minecraft server");
    }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing pinned " + name);
        return value;
    }
    static void copyFixture(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (!relative.toString().isEmpty() && relative.getName(0).toString().equals(".git")) continue;
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Fixture contains a symlink: " + path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(destination);
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.copy(path, destination);
                else throw new IllegalArgumentException("Unsupported fixture file: " + path);
            }
        }
    }
}
