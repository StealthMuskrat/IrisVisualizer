package art.arcane.iris.probe;

import art.arcane.iris.generation.terrain.IrisDimension;
import art.arcane.iris.generation.runtime.IrisEngine;
import art.arcane.iris.structure.nativegen.NativeStructureOwnershipRecord;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeStructurePostProcessor;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeStructureTerrainIntegrator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Isolated native service audit. Its disposable partial blocks never become a preview. */
final class NativeStructureBlockBoundary {
    private NativeStructureBlockBoundary() {}

    static void run(HeadlessTerrainContext context, IrisDimension dimension,
                    HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord> planned,
                    HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord> other,
                    Path output, Properties report) throws Exception {
        var nativeContext = context.structures();
        if (!(context.engine() instanceof IrisEngine engine))
            throw new IllegalArgumentException("The real-pack boundary audit requires its Iris engine");
        report.setProperty("generationAttempted", "true");
        report.setProperty("terrainGenerationAttempted", "false");
        var structure = nativeContext.registries().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:ancient_city"))).value();
        var original = planned.chunk().getStartForStructure(structure);
        var saved = original.createTag(context.serialization().structures(), planned.chunk().getPos());
        var terrain = planned.terrain().get(structure);
        if (terrain == null) throw new IllegalStateException("Configured ancient-city terrain settings were lost");
        List<Result> results = new ArrayList<>();
        for (String phase : List.of("terrain-preparation", "native-pieces")) {
            StructureStart start = StructureStart.loadStaticStart(context.serialization().structures(), saved.copy(),
                    context.engine().getSeedManager().getSeed());
            if (start == null || !start.isValid() || start.getPieces().isEmpty())
                throw new IllegalStateException("Ancient-city start did not survive native NBT loading");
            var centerBlock = start.getPieces().getFirst().getBoundingBox().getCenter();
            ChunkPos center = new ChunkPos(centerBlock.getX() >> 4, centerBlock.getZ() >> 4);
            Fixture fixture = fixture(context, dimension, center);
            BoundingBox area = new BoundingBox(center.getMinBlockX(), context.engine().getMinHeight() + 1,
                    center.getMinBlockZ(), center.getMaxBlockX(), context.engine().getMaxHeight() - 1, center.getMaxBlockZ());
            RuntimeException blocked = null;
            try {
                if (phase.equals("terrain-preparation")) {
                    NativeStructurePostProcessor.prepareTerrain(fixture.world(), area,
                            List.of(new NativeStructureTerrainIntegrator.TerrainTarget("minecraft:ancient_city", start, terrain)),
                            (palette, random, x, y, z) -> {
                                throw new IllegalStateException("Unexpected palette lookup during ancient-city FORCE_CARVE preparation");
                            });
                } else {
                    var random = decorationRandom(context, structure, center);
                    start.placeInChunk(fixture.world(), new StructureManager(fixture.world(), nativeContext.worldOptions(), null),
                            nativeContext.generator(), random, area, center);
                }
            } catch (RuntimeException failure) {
                if (!requiresServerLevel(failure)) throw failure;
                blocked = failure;
                failure.printStackTrace(System.out);
                try (var writer = new PrintWriter(Files.newBufferedWriter(output.resolve(phase + "-failure.txt"),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW))) { failure.printStackTrace(writer); }
            }
            int nonAir = countNonAir(fixture.chunks());
            if (blocked == null && phase.equals("native-pieces") && nonAir == 0)
                throw new IllegalStateException("Native piece call completed without placing blocks");
            for (var chunk : fixture.chunks()) {
                if (chunk.getPersistedStatus() != ChunkStatus.TERRAIN)
                    throw new IllegalStateException("Isolated partial blocks were promoted beyond TERRAIN");
            }
            results.add(new Result(phase, blocked == null ? "COMPLETED_ISOLATED" : "BLOCKED",
                    blocked == null ? "" : "ServerLevel", nonAir));
            NativeFixtureProof.requireNoReports(engine);
            NativeFixtureProof.requireNoServer();
        }
        if (!saved.equals(original.createTag(context.serialization().structures(), planned.chunk().getPos())))
            throw new IllegalStateException("Isolated block audit changed the planned source start");
        if (results.stream().noneMatch(result -> result.phase().equals("terrain-preparation") && result.status().equals("BLOCKED")))
            throw new IllegalStateException("The known real-pack ServerLevel dependency was not reproduced; reassess the backend boundary");
        try (var writer = Files.newBufferedWriter(output.resolve("structure-block-boundaries.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("phase\tstatus\tmissingService\tisolatedNonAirBlocks\tresource\tpropertyPath\n");
            for (var result : results) {
                writer.write(result.phase() + "\t" + result.status() + "\t" + result.service() + "\t" + result.nonAir()
                        + "\tdimensions/overworld.json\t/structures/0" + (result.phase().equals("terrain-preparation") ? "/terrain" : "/nativeStructures/0/structure") + "\n");
                System.out.println("[structure-block-boundary] phase=" + result.phase() + " status=" + result.status()
                        + " missingService=" + result.service() + " isolatedNonAirBlocks=" + result.nonAir());
            }
        }
        report.setProperty("structureBlockBoundaryAudited", "true");
        report.setProperty("structureBlocksPlaced", Boolean.toString(results.stream().anyMatch(result -> result.nonAir() > 0)));
        report.setProperty("structureStageComplete", "false");
        report.setProperty("partialBlockResultsDiscarded", "true");
        report.setProperty("structureBlockFixture", "isolated-air-input-not-generated-terrain");
        report.setProperty("structureBlockFixtureHeight", Integer.toString(context.engine().getHeight()));
        report.setProperty("structureBlockFixtureMinY", Integer.toString(context.engine().getMinHeight()));
        report.setProperty("structureTerrainMissingService", "ServerLevel");
        report.setProperty("isolatedPieceStatus", results.get(1).status());
        NativeTemplatePreparationProof.run(context, dimension, List.of(planned, other), output, report);
        NativePieceDispatchProof.run(context, dimension, List.of(planned, other), output, report);
        NativeIntegratedStructureProof.run(context, dimension, List.of(planned, other), output, report);
    }

    static WorldgenRandom decorationRandom(HeadlessTerrainContext context, Structure structure, ChunkPos center) {
        int index = 0;
        for (var candidate : context.structures().registries().lookupOrThrow(Registries.STRUCTURE)) {
            if (candidate == structure) break;
            if (candidate.step() == structure.step()) index++;
        }
        var random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(context.engine().getSeedManager().getSeed(), center.getMinBlockX(), center.getMinBlockZ());
        random.setFeatureSeed(decorationSeed, index, structure.step().ordinal());
        return random;
    }

    static Fixture fixture(HeadlessTerrainContext context, IrisDimension dimension, ChunkPos center) {
        var registries = context.structures().registries();
        var type = registries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(ResourceKey.create(
                Registries.DIMENSION_TYPE, Identifier.parse("iris:" + dimension.getDimensionTypeKey()))).value();
        if (type.minY() != context.engine().getMinHeight() || type.height() != context.engine().getHeight())
            throw new IllegalStateException("Native dimension type differs from configured full-height bounds");
        List<ProtoChunk> chunks = new ArrayList<>();
        context.biomes().prepareVisibleBiomeBatch();
        var biomes = context.biomes().visibleResolver();
        for (int x = center.x() - 1; x <= center.x() + 1; x++) for (int z = center.z() - 1; z <= center.z() + 1; z++) {
            var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY,
                    LevelHeightAccessor.create(type.minY(), type.height()), PalettedContainerFactory.create(registries), null);
            chunk.fillBiomesFromNoise(biomes::biome);
            chunk.setPersistedStatus(ChunkStatus.TERRAIN);
            chunks.add(chunk);
        }
        return new Fixture(new OfflineFeatureWorld(new OfflineFeatureWorld.Options(registries, type,
                context.engine().getSeedManager().getSeed(), context.structures().generator().getSeaLevel()), chunks), List.copyOf(chunks));
    }

    private static boolean requiresServerLevel(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnsupportedOperationException && current.getMessage() != null
                    && current.getMessage().equals("Offline feature fixture has no ServerLevel")) {
                for (var frame : current.getStackTrace()) {
                    if (frame.getClassName().equals(OfflineFeatureWorld.class.getName())
                            && List.of("getLevel", "getMinecraftWorld").contains(frame.getMethodName())) return true;
                }
            }
        }
        return false;
    }

    private static int countNonAir(List<ProtoChunk> chunks) {
        int count = 0;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (var chunk : chunks) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            for (int y = chunk.getMinY(); y < chunk.getMinY() + chunk.getHeight(); y++) {
                position.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                if (!chunk.getBlockState(position).isAir()) count++;
            }
        return count;
    }
    record Fixture(OfflineFeatureWorld world, List<ProtoChunk> chunks) {}
    private record Result(String phase, String status, String service, int nonAir) {}
}
