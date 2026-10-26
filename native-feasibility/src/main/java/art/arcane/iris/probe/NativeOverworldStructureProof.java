package art.arcane.iris.probe;

import art.arcane.iris.generation.context.IrisContext;
import art.arcane.iris.generation.runtime.EnginePlatformHooks;
import art.arcane.iris.generation.runtime.EngineTarget;
import art.arcane.iris.generation.runtime.IrisEngine;
import art.arcane.iris.generation.terrain.IrisDimension;
import art.arcane.iris.pack.loading.IrisData;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.structure.nativegen.NativeStructureOwnershipRecord;
import art.arcane.iris.structure.nativegen.NativeStructurePlacementPlanner;
import art.arcane.iris.structure.nativegen.NativeStructureStartPlan;
import art.arcane.iris.world.IrisWorld;
import art.arcane.iris.world.history.GenerationHistoryPaths;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeStructureFactory;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeStructureOwnershipFingerprint;
import dev.irisvisualizer.feasibility.PackFingerprint;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

/** Official start/reference stages only. No structure blocks, terrain or features are published. */
final class NativeOverworldStructureProof {
    private static final long SEED = 1337L;
    private static final String CITY = "minecraft:ancient_city";
    private NativeOverworldStructureProof() {}

    static void run(IrisData data, IrisDimension dimension, HeadlessNativeRuntime runtime,
                    Path scratch, Path output, Properties report, boolean auditBlocks) throws Exception {
        report.setProperty("structurePlanningAttempted", "true");
        report.setProperty("structureBlocksPlaced", "false");
        report.setProperty("featuresGenerated", "false");
        report.setProperty("seed", Long.toString(SEED));
        // This explicit developer fixture is not an implicit application/world default.
        Path configuration = Path.of(NativeFixtureProof.required("IRIS_VISUALIZER_ROOT"))
                .resolve("native-feasibility/src/test/resources/spigot-structure-context.yml");
        byte[] configBytes = Files.readAllBytes(configuration);
        report.setProperty("structureConfigurationSha256", HexFormat.of().formatHex(PackFingerprint.digest().digest(configBytes)));
        report.setProperty("structureLevelKey", "minecraft:overworld");
        report.setProperty("structureNoiseSettings", "minecraft:overworld");
        IrisServices.register(EnginePlatformHooks.class, runtime);
        Path world = scratch.resolve("world");
        var target = IrisWorld.builder().platformIdentity("iris:visualizer-structure-proof").name("structure-proof")
                .seed(SEED).worldFolder(world.toFile()).minHeight(dimension.getMinHeight()).maxHeight(dimension.getMaxHeight()).build();
        var engine = new IrisEngine(new EngineTarget(target, dimension, data), IrisEngine.InitializationMode.RUNTIME);
        try (AutoCloseable closeEngine = engine::close;
             var lease = engine.acquireGenerationLease("visualizer_structure_proof");
             var scope = IrisContext.open(engine, lease.sessionId(), null)) {
            if (engine.getGenerationHistoryRuntimeRouter().isPresent())
                throw new IllegalStateException("Structure proof must not attach generation history");
            var config = HeadlessStructureConfiguration.load(new HeadlessStructureConfiguration.Options(
                    configuration, "structure-proof", Level.OVERWORLD, scratch.resolve("configuration")));
            try (var context = HeadlessTerrainContext.create(new HeadlessTerrainContext.Options(engine, runtime,
                    scratch.resolve("templates"), Level.OVERWORLD, NoiseGeneratorSettings.OVERWORLD, config))) {
                NativeFixtureProof.requireNoReports(engine);
                NativeStructureStartPlan negative = findCity(engine, dimension, -128, 0);
                NativeStructureStartPlan positive = findCity(engine, dimension, 0, 128);
                Map<Long, HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord>> grid = new HashMap<>();
                ChunkPos center = new ChunkPos(negative.chunkX(), negative.chunkZ());
                // Native references require the complete radius-eight origin halo, including its edges.
                for (int x = center.x() - 8; x <= center.x() + 8; x++) {
                    for (int z = center.z() - 8; z <= center.z() + 8; z++) {
                        var position = new ChunkPos(x, z);
                        grid.put(position.pack(), HeadlessStructurePlanner.plan(context.structures(), context.policies(), position));
                    }
                    System.out.println("[overworld-structures] planned origin rows=" + (x - center.x() + 9) + "/17");
                    NativeFixtureProof.requireNoReports(engine);
                }
                var first = grid.get(center.pack());
                compareCity(context, first, negative);
                var other = HeadlessStructurePlanner.plan(context.structures(), context.policies(),
                        new ChunkPos(positive.chunkX(), positive.chunkZ()));
                compareCity(context, other, positive);
                var missing = new HashMap<>(grid);
                missing.remove(new ChunkPos(center.x() - 8, center.z() - 8).pack());
                boolean rejected = false;
                try { HeadlessStructurePlanner.references(context.policies(), first, missing); }
                catch (IllegalArgumentException expected) { rejected = true; }
                if (!rejected || first.chunk().getPersistedStatus() != ChunkStatus.STRUCTURE_STARTS
                        || !first.chunk().getAllReferences().isEmpty())
                    throw new IllegalStateException("Incomplete origin halo was accepted or modified the target");
                HeadlessStructurePlanner.references(context.policies(), first, grid);
                var city = context.structures().registries().lookupOrThrow(Registries.STRUCTURE)
                        .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(CITY))).value();
                var references = first.chunk().getAllReferences().get(city);
                if (first.chunk().getPersistedStatus() != ChunkStatus.STRUCTURE_REFERENCES
                        || references == null || !references.contains(center.pack()))
                    throw new IllegalStateException("Configured ancient-city origin reference was lost");
                grid.put(other.chunk().getPos().pack(), other);
                writeStarts(context, grid, output);
                report.setProperty("plannedOriginChunks", Integer.toString(grid.size()));
                report.setProperty("ancientCityFactoryComparisons", "2");
                report.setProperty("negativeCityChunk", center.x() + "," + center.z());
                report.setProperty("positiveCityChunk", positive.chunkX() + "," + positive.chunkZ());
                report.setProperty("missingOriginHaloRejected", "true");
                report.setProperty("ancientCityOriginReferenceRetained", "true");
                report.setProperty("structurePlanningPassed", "true");
                if (auditBlocks) NativeStructureBlockBoundary.run(context, dimension, first, output, report);
            }
            NativeFixtureProof.requireNoReports(engine);
        }
        NativeFixtureProof.requireNoReports(null);
        NativeFixtureProof.requireNoServer();
        if (Files.exists(GenerationHistoryPaths.forDimension(world).generationRoot()))
            throw new IllegalStateException("Structure planning created generation history");
        if (!java.util.Arrays.equals(configBytes, Files.readAllBytes(configuration)))
            throw new IllegalStateException("Native configuration loader changed its source fixture");
        report.setProperty("historyDirectoryAbsent", "true");
        System.out.println("[overworld-structures] native ancient-city starts, ownership, factory comparisons and origin references passed");
    }

    private static NativeStructureStartPlan findCity(IrisEngine engine, IrisDimension dimension, int min, int max) {
        for (var placement : dimension.getStructures()) {
            if (!placement.hasNativeStructures()) continue;
            // The official planner decides spacing, eligibility, selection and height; this only bounds the probe search.
            for (int x = min; x < max; x++) for (int z = min; z < max; z++) {
                var plan = NativeStructurePlacementPlanner.planAt(engine, placement, x, z);
                if (plan != null && plan.structureKey().equals(CITY)) return plan;
            }
        }
        throw new IllegalStateException("No eligible ancient-city start in the bounded quadrant " + min + ".." + max);
    }

    private static void compareCity(HeadlessTerrainContext context,
            HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord> actual, NativeStructureStartPlan plan) {
        var nativeContext = context.structures();
        var holder = nativeContext.registries().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(CITY)));
        var start = actual.chunk().getStartForStructure(holder.value());
        if (start == null || !start.isValid() || start.getPieces().isEmpty())
            throw new IllegalStateException("Native ancient-city pieces were not planned");
        var ownership = actual.ownership().get(holder.value());
        if (!NativeStructureOwnershipFingerprint.matches(ownership, start))
            throw new IllegalStateException("Ancient-city ownership does not match its native pieces");
        var expected = NativeStructureFactory.generate(new NativeStructureFactory.GenerationContext(
                nativeContext.registries(), nativeContext.generator(), nativeContext.generator().getBiomeSource(),
                nativeContext.structureState().randomState(), nativeContext.templates(), SEED, Level.OVERWORLD,
                actual.chunk(), biome -> true, nativeContext.generator().getSeaLevel(), context.policies().injection()::surfaceHeight),
                holder, plan, 0);
        if (!expected.isValid() || !expected.createTag(context.serialization().structures(), actual.chunk().getPos())
                .equals(start.createTag(context.serialization().structures(), actual.chunk().getPos())))
            throw new IllegalStateException("Planned ancient-city NBT differs from the direct official native factory");
    }

    private static void writeStarts(HeadlessTerrainContext context,
            Map<Long, HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord>> grid, Path output) throws Exception {
        var registry = context.structures().registries().lookupOrThrow(Registries.STRUCTURE);
        try (var writer = Files.newBufferedWriter(output.resolve("structure-starts.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("chunkX\tchunkZ\tstructure\tpieces\tconfiguredOwnership\n");
            for (var chunk : grid.values().stream().sorted(Comparator
                    .comparingInt((HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord> value) -> value.chunk().getPos().x())
                    .thenComparingInt(value -> value.chunk().getPos().z())).toList()) {
                for (var entry : chunk.chunk().getAllStarts().entrySet().stream()
                        .sorted(Comparator.comparing(entry -> registry.getKey(entry.getKey()).toString())).toList()) {
                    if (!entry.getValue().isValid()) continue;
                    writer.write(chunk.chunk().getPos().x() + "\t" + chunk.chunk().getPos().z() + "\t" + registry.getKey(entry.getKey())
                            + "\t" + entry.getValue().getPieces().size() + "\t" + chunk.ownership().containsKey(entry.getKey()) + "\n");
                }
            }
        }
    }
}
