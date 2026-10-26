package art.arcane.iris.probe;

import art.arcane.iris.generation.terrain.IrisDimension;
import art.arcane.iris.structure.nativegen.NativeStructureOwnershipRecord;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeStructurePostProcessor;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeStructureTerrainIntegrator;
import dev.irisvisualizer.feasibility.PackFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

/** Measures a template-only service injection. Reference mocks are confined to this development proof. */
final class NativeTemplatePreparationProof {
    private NativeTemplatePreparationProof() {}

    static void run(HeadlessTerrainContext context, IrisDimension dimension,
                    List<HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord>> planned,
                    Path output, Properties report) throws Exception {
        int cases = 0;
        try (var writer = Files.newBufferedWriter(output.resolve("template-preparation.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("originChunkX\toriginChunkZ\tminY\theight\tcarvedBlocks\ttemplateRequests\tstandaloneSha256\tlegacySha256\n");
            for (var origin : planned) {
                var standalone = prepare(context, dimension, origin, false);
                var legacy = prepare(context, dimension, origin, true);
                if (standalone.carved() <= 0 || standalone.carved() >= 16 * 16 * context.engine().getHeight()
                        || standalone.carved() != legacy.carved() || !standalone.hash().equals(legacy.hash()))
                    throw new IllegalStateException("Standalone native carving differs from legacy template lookup");
                writer.write(origin.chunk().getPos().x() + "\t" + origin.chunk().getPos().z() + "\t" + context.engine().getMinHeight()
                        + "\t" + context.engine().getHeight() + "\t" + standalone.carved() + "\t" + standalone.templateRequests()
                        + "\t" + standalone.hash() + "\t" + legacy.hash() + "\n");
                System.out.println("[template-preparation] origin=" + origin.chunk().getPos().x() + "," + origin.chunk().getPos().z()
                        + " carvedBlocks=" + standalone.carved() + " templateRequests=" + standalone.templateRequests()
                        + " legacyHashesMatch=true serverWorldUsedByStandalone=false");
                cases++;
            }
        }
        NativeFixtureProof.requireNoServer();
        report.setProperty("templatePreparationPassed", "true");
        report.setProperty("templatePreparationCases", Integer.toString(cases));
        report.setProperty("templatePreparationHashesMatch", "true");
        report.setProperty("templateFixture", "isolated-solid-input-not-generated-terrain");
        report.setProperty("templateStandaloneServerWorldUsed", "false");
        report.setProperty("templateLegacyReference", "native-legacy-method-with-template-only-test-double");
        report.setProperty("templatePreparationResultsDiscarded", "true");
    }

    private static Result prepare(HeadlessTerrainContext context, IrisDimension dimension,
                    HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord> planned, boolean reference) {
        var structure = context.structures().registries().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:ancient_city"))).value();
        var original = planned.chunk().getStartForStructure(structure);
        var saved = original.createTag(context.serialization().structures(), planned.chunk().getPos());
        var start = StructureStart.loadStaticStart(context.serialization().structures(), saved.copy(), context.engine().getSeedManager().getSeed());
        if (start == null || !start.isValid()) throw new IllegalStateException("Native template start did not restore");
        var centerBlock = start.getPieces().getFirst().getBoundingBox().getCenter();
        var center = new ChunkPos(centerBlock.getX() >> 4, centerBlock.getZ() >> 4);
        var fixture = NativeStructureBlockBoundary.fixture(context, dimension, center);
        var chunk = fixture.chunks().stream().filter(value -> value.getPos().equals(center)).findFirst().orElseThrow();
        var position = new BlockPos.MutableBlockPos();
        for (int x = center.getMinBlockX(); x <= center.getMaxBlockX(); x++)
            for (int z = center.getMinBlockZ(); z <= center.getMaxBlockZ(); z++)
                for (int y = chunk.getMinY(); y < chunk.getMinY() + chunk.getHeight(); y++)
                    chunk.setBlockState(position.set(x, y, z), Blocks.STONE.defaultBlockState(), 2);
        var area = new BoundingBox(center.getMinBlockX(), chunk.getMinY() + 1, center.getMinBlockZ(),
                center.getMaxBlockX(), chunk.getMinY() + chunk.getHeight() - 1, center.getMaxBlockZ());
        var targets = List.of(new NativeStructureTerrainIntegrator.TerrainTarget("minecraft:ancient_city", start, planned.terrain().get(structure)));
        NativeStructureTerrainIntegrator.PaletteBlockResolver palette = (source, random, x, y, z) -> {
            throw new IllegalStateException("Unexpected palette lookup during native FORCE_CARVE test");
        };
        AtomicInteger requests = new AtomicInteger();
        if (reference) {
            // Only the reference method receives a test double. The standalone call below keeps getLevel() throwing.
            ServerLevel level = mock(ServerLevel.class);
            when(level.getStructureTemplateManager()).thenReturn(context.structures().templates());
            WorldGenLevel legacy = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getLevel")) return level;
                        try { return method.invoke(fixture.world(), args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            NativeStructurePostProcessor.prepareTerrain(legacy, area, targets, palette);
            verify(level, atLeastOnce()).getStructureTemplateManager();
            verifyNoMoreInteractions(level);
        } else {
            NativeStructurePostProcessor.prepareTerrain(fixture.world(), area, targets, palette, () -> {
                requests.incrementAndGet();
                return context.structures().templates();
            });
            if (requests.get() == 0) throw new IllegalStateException("Standalone native path did not use its explicit template service");
        }
        int carved = 0;
        var hash = PackFingerprint.digest();
        for (int x = center.getMinBlockX(); x <= center.getMaxBlockX(); x++)
            for (int z = center.getMinBlockZ(); z <= center.getMaxBlockZ(); z++)
                for (int y = chunk.getMinY(); y < chunk.getMinY() + chunk.getHeight(); y++) {
                    var state = chunk.getBlockState(position.set(x, y, z));
                    if (state.isAir()) carved++;
                    else if (!state.is(Blocks.STONE)) throw new IllegalStateException("Unexpected native carve output: " + state);
                    hash.update((byte) (state.isAir() ? 0 : 1));
                }
        for (var value : fixture.chunks()) if (value.getPersistedStatus() != ChunkStatus.TERRAIN)
            throw new IllegalStateException("Template preparation promoted an isolated chunk");
        if (!saved.equals(original.createTag(context.serialization().structures(), planned.chunk().getPos())))
            throw new IllegalStateException("Native template preparation changed original start NBT");
        NativeFixtureProof.requireNoServer();
        return new Result(carved, requests.get(), HexFormat.of().formatHex(hash.digest()));
    }
    private record Result(int carved, int templateRequests, String hash) {}
}
