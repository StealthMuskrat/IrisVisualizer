package art.arcane.iris.probe;

import art.arcane.iris.generation.terrain.IrisDimension;
import art.arcane.iris.pack.PackValidator;
import art.arcane.iris.pack.datapack.v263.DataFixerV263;
import art.arcane.iris.pack.loading.IrisData;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.structure.placement.IrisStructurePlacement;
import art.arcane.iris.world.history.GenerationPackFingerprint;
import art.arcane.iris.world.history.GenerationRegistryContractFactory;
import dev.irisvisualizer.engine.PreviewReadiness;
import dev.irisvisualizer.feasibility.PackFingerprint;
import dev.irisvisualizer.feasibility.PreviewBudget;
import net.minecraft.SharedConstants;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Loads the actual pinned Overworld native context. It never exposes incomplete terrain as preview-ready. */
public final class NativeOverworldContextAudit {
    private static volatile Object retainedIndexBuffers;
    private NativeOverworldContextAudit() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && !(args.length == 3 && List.of("structures", "blocks").contains(args[2])))
            throw new IllegalArgumentException("Expected: pinned-overworld-pack report-directory [structures|blocks]");
        boolean structures = args.length == 3;
        boolean blocks = structures && args[2].equals("blocks");
        if (!System.getProperty("os.name").startsWith("Windows") || Runtime.version().feature() != 25
                || !List.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw new IllegalStateException("Windows x64 and Java 25 are required");
        Path source = Path.of(args[0]).toRealPath(), output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        String before = PackFingerprint.sha256(source);
        Properties report = new Properties();
        report.setProperty("status", "FAILED");
        report.setProperty("previewAllowed", "false");
        report.setProperty("nativeParity", "NOT_TESTED");
        report.setProperty("generationAttempted", "false");
        report.setProperty("irisVersion", NativeFixtureProof.required("IRIS_VISUALIZER_IRIS_VERSION"));
        report.setProperty("irisRevision", NativeFixtureProof.required("IRIS_VISUALIZER_IRIS_REVISION"));
        report.setProperty("volmlibRevision", NativeFixtureProof.required("IRIS_VISUALIZER_VOLMLIB_REVISION"));
        report.setProperty("volmlibCompatibilitySha256", NativeFixtureProof.required("IRIS_VISUALIZER_VOLMLIB_COMPATIBILITY_SHA256"));
        report.setProperty("volmlibTemplateServicesSha256", NativeFixtureProof.required("IRIS_VISUALIZER_VOLMLIB_TEMPLATE_SERVICES_SHA256"));
        report.setProperty("packRevision", NativeFixtureProof.required("IRIS_VISUALIZER_PACK_REVISION"));
        report.setProperty("packSha256", before);
        report.setProperty("dimension", "overworld");
        Path scratch = Files.createTempDirectory("iris-visualizer-overworld-context-");
        long started = System.nanoTime();
        try {
            Path pack = scratch.resolve("pack");
            NativeFixtureProof.copyFixture(source, pack);
            if (!before.equals(PackFingerprint.sha256(pack))) throw new IllegalStateException("Pack data changed while copying the native input");
            inspect(pack, scratch, output, report, structures, blocks);
            ProbeWorkspaceCleanup.deleteRecursively(scratch);
            if (Files.exists(scratch)) throw new IllegalStateException("Overworld context workspace was not deleted");
            NativeFixtureProof.requireNoServer();
            report.setProperty("workspaceDeleted", "true");
            report.setProperty("status", "NATIVE_OVERWORLD_CONTEXT_AUDIT_PASSED");
        } catch (Exception | Error failure) {
            report.setProperty("failure", failure.getClass().getName() + ": " + failure.getMessage());
            try { ProbeWorkspaceCleanup.deleteRecursively(scratch); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            retainedIndexBuffers = null;
            try {
                boolean unchanged = before.equals(PackFingerprint.sha256(source));
                report.setProperty("sourcePackUnchanged", Boolean.toString(unchanged));
                if (!unchanged) {
                    report.setProperty("status", "FAILED");
                    throw new IllegalStateException("The native context audit changed the source pack");
                }
            } finally {
                report.setProperty("elapsedMillis", Long.toString((System.nanoTime() - started) / 1_000_000));
                report.setProperty("heapPoolPeakBytesSum", Long.toString(ManagementFactory.getMemoryPoolMXBeans().stream()
                        .filter(pool -> pool.getType() == MemoryType.HEAP).mapToLong(pool -> pool.getPeakUsage().getUsed()).sum()));
                try (var writer = Files.newBufferedWriter(output.resolve("report.properties"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                    report.store(writer, "Native context audit passed does not mean a complete preview is ready");
                }
            }
        }
        System.out.println("NATIVE_OVERWORLD_CONTEXT_AUDIT_PASSED previewAllowed=false nativeParity=NOT_TESTED sourcePackUnchanged=true");
    }
    private static void inspect(Path pack, Path scratch, Path output, Properties report, boolean structures, boolean blocks) throws Exception {
        NativeFixtureProof.requireNoServer();
        try (var workspace = RealPackProbeSupport.openPreparedWorkspace(pack.toFile(), "overworld", "[overworld-context]", false);
             var runtime = new HeadlessNativeRuntime()) {
            IrisPlatforms.unbind();
            var platform = runtime.create(scratch.resolve("platform").toFile());
            IrisPlatforms.bind(platform);
            String minecraft = SharedConstants.getCurrentVersion().name();
            if (!minecraft.equals(NativeFixtureProof.required("IRIS_VISUALIZER_MINECRAFT_VERSION")))
                throw new IllegalStateException("Native Minecraft version differs from the pinned context");
            report.setProperty("minecraftVersion", minecraft);
            IrisData data = IrisData.openRuntime(pack.toFile());
            try (AutoCloseable closeData = data::close) {
                IrisDimension dimension = data.getDimensionLoader().load("overworld");
                if (dimension == null) throw new IllegalStateException("Overworld dimension did not load");
                runtime.prepare(data, dimension);
                var validation = PackValidator.validateForDatapackBootstrap(pack.toFile());
                for (String warning : validation.getWarnings()) System.out.println("[overworld-context] warning: " + warning);
                if (!validation.isLoadable()) throw new IllegalStateException("Pack validation failed: " + validation.getBlockingErrors());
                String fingerprint = GenerationPackFingerprint.compute(pack, GenerationPackFingerprint.CURRENT_VERSION);
                data.bindGenerationRegistryContract(GenerationRegistryContractFactory.create(data, dimension, fingerprint,
                        new DataFixerV263(), platform.registries(), platform.registries().generationRegistry()));
                NativeFixtureProof.requireNoReports(null);
                report.setProperty("nativeRegistryContractLoaded", "true");
                report.setProperty("registeredBiomeKeys", Integer.toString(platform.registries().biomeKeys().size()));
                report.setProperty("registeredBlockKeys", Integer.toString(platform.registries().blockKeys().size()));
                List<PreviewReadiness.NativePlacement> nativePlacements = new ArrayList<>();
                collect(nativePlacements, "dimensions", dimension.getLoadKey(), dimension.getStructures());
                var regions = dimension.getAllRegions(() -> data);
                var biomes = dimension.getAllBiomes(() -> data);
                for (var region : regions) collect(nativePlacements, "regions", region.getLoadKey(), region.getStructures());
                for (var biome : biomes) collect(nativePlacements, "biomes", biome.getLoadKey(), biome.getStructures());
                nativePlacements.sort(Comparator.comparing(PreviewReadiness.NativePlacement::resourceType)
                        .thenComparing(PreviewReadiness.NativePlacement::resourceKey)
                        .thenComparing(PreviewReadiness.NativePlacement::propertyPath));
                for (var placement : nativePlacements) {
                    if (!platform.registries().structureKeys().contains(placement.structureKey()))
                        throw new IllegalStateException("Unregistered native structure " + placement.structureKey() + " at " + placement.resourceType() + "/" + placement.resourceKey() + ".json#" + placement.propertyPath());
                }
                var readiness = PreviewReadiness.evaluateWorld(dimension.getLoadKey(),
                        new PreviewReadiness.Capabilities(false, false, false), nativePlacements);
                if (readiness.previewAllowed()) throw new IllegalStateException("An incomplete native backend was marked preview-ready");
                report.setProperty("reachableRegions", Integer.toString(regions.size()));
                report.setProperty("reachableBiomes", Integer.toString(biomes.size()));
                report.setProperty("nativePlacements", Integer.toString(nativePlacements.size()));
                report.setProperty("previewBlockers", Integer.toString(readiness.blockers().size()));
                writeDiagnostics(output, nativePlacements, readiness);
                checkIndexAllocation(dimension, report);
                retainedIndexBuffers = null;
                if (structures) NativeOverworldStructureProof.run(data, dimension, runtime, scratch, output, report, blocks);
                System.out.println("[overworld-context] native registry contract loaded; regions=" + regions.size()
                        + " biomes=" + biomes.size() + " nativePlacements=" + nativePlacements.size()
                        + " previewBlockers=" + readiness.blockers().size());
            }
        }
        NativeFixtureProof.requireNoReports(null);
        NativeFixtureProof.requireNoServer();
        if (!structures && Files.exists(scratch.resolve("world"))) throw new IllegalStateException("Context-only audit unexpectedly generated a world");
    }
    private static void collect(List<PreviewReadiness.NativePlacement> result, String type, String key,
                                List<IrisStructurePlacement> placements) {
        if (placements == null) return;
        for (int i = 0; i < placements.size(); i++) {
            var placement = placements.get(i);
            if (placement == null || !placement.hasNativeStructures()) continue;
            for (int j = 0; j < placement.getNativeStructures().size(); j++) {
                var structure = placement.getNativeStructures().get(j);
                if (structure == null) throw new IllegalStateException("Null native structure reference in " + type + "/" + key);
                result.add(new PreviewReadiness.NativePlacement(type, key,
                        "/structures/" + i + "/nativeStructures/" + j + "/structure", structure.getStructure()));
            }
        }
    }
    private static void checkIndexAllocation(IrisDimension dimension, Properties report) {
        int min = dimension.getMinHeight(), max = dimension.getMaxHeight();
        PreviewBudget.PROPOSED.requireVolume(128, 128, min, max);
        long bytes = PreviewBudget.PROPOSED.volumeBytes(128, 128, min, max);
        int cells = Math.toIntExact(bytes / (2 * Integer.BYTES));
        int[] blocks = new int[cells], biomes = new int[cells];
        retainedIndexBuffers = new Object[]{blocks, biomes};
        for (int i = 0; i < cells; i++) { blocks[i] = i; biomes[i] = ~i; }
        long checksum = 0;
        for (int i = 0; i < cells; i++) checksum += blocks[i] + biomes[i];
        report.setProperty("minY", Integer.toString(min)); report.setProperty("maxYExclusive", Integer.toString(max));
        report.setProperty("previewArea", "128x128"); report.setProperty("indexAllocationBytes", Long.toString(bytes));
        report.setProperty("indexAllocationChecksum", Long.toString(checksum));
        report.setProperty("indexAllocationExercised", "true"); report.setProperty("cacheBudgetsConfirmed", "false");
        System.out.println("[overworld-context] full-height index allocation=" + bytes + " bytes; no terrain generation performed");
    }
    private static void writeDiagnostics(Path output, List<PreviewReadiness.NativePlacement> placements,
                                         PreviewReadiness.Result readiness) throws Exception {
        try (var writer = Files.newBufferedWriter(output.resolve("native-placements.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("resourceType\tresourceKey\tpropertyPath\tstructureKey\n");
            for (var item : placements) writer.write(item.resourceType() + "\t" + item.resourceKey() + "\t" + item.propertyPath() + "\t" + item.structureKey() + "\n");
        }
        try (var writer = Files.newBufferedWriter(output.resolve("preview-blockers.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("code\tresourceType\tresourceKey\tpropertyPath\tmessage\n");
            for (var item : readiness.blockers()) writer.write(item.code() + "\t" + item.resourceType() + "\t" + item.resourceKey()
                    + "\t" + item.propertyPath() + "\t" + item.message() + "\n");
        }
    }
}
