package art.arcane.iris.probe;

import art.arcane.iris.generation.terrain.IrisDimension;
import art.arcane.iris.structure.nativegen.NativeStructureOwnershipRecord;
import dev.irisvisualizer.engine.NativePieceDispatcher;
import dev.irisvisualizer.feasibility.PackFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.bukkit.craftbukkit.util.CraftStructureTransformer;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.mockito.Mockito.*;

/** Compares native piece dispatch with the actual runtime's inactive plugin-callback reference. */
final class NativePieceDispatchProof {
    private NativePieceDispatchProof() {}

    static void run(HeadlessTerrainContext context, IrisDimension dimension,
                    List<HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord>> planned,
                    Path output, Properties report) throws Exception {
        dispatchContract();
        try (var stream = StructureStart.class.getResourceAsStream("StructureStart.class")) {
            if (stream == null) throw new IllegalStateException("Native StructureStart bytecode is unavailable");
            report.setProperty("nativeStructureStartClassSha256", HexFormat.of().formatHex(PackFingerprint.digest().digest(stream.readAllBytes())));
        }
        int cases = 0;
        try (var writer = Files.newBufferedWriter(output.resolve("native-piece-dispatch.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("originChunkX\toriginChunkZ\tminY\theight\tnonAirBlocks\tblockStates\tstandaloneSha256\tlegacySha256\n");
            for (var origin : planned) {
                var standalone = place(context, dimension, origin, false);
                var legacy = place(context, dimension, origin, true);
                if (standalone.nonAir() <= 0 || standalone.states() < 2 || !standalone.equals(legacy))
                    throw new IllegalStateException("Standalone native piece output differs from original runtime placement");
                writer.write(origin.chunk().getPos().x() + "\t" + origin.chunk().getPos().z() + "\t" + context.engine().getMinHeight()
                        + "\t" + context.engine().getHeight() + "\t" + standalone.nonAir() + "\t" + standalone.states()
                        + "\t" + standalone.hash() + "\t" + legacy.hash() + "\n");
                System.out.println("[native-piece-dispatch] origin=" + origin.chunk().getPos().x() + "," + origin.chunk().getPos().z()
                        + " nonAirBlocks=" + standalone.nonAir() + " blockStates=" + standalone.states()
                        + " legacyHashesMatch=true serverWorldUsedByStandalone=false");
                cases++;
            }
        }
        report.setProperty("nativePieceDispatchPassed", "true");
        report.setProperty("nativePieceDispatchCases", Integer.toString(cases));
        report.setProperty("nativePieceDispatchHashesMatch", "true");
        report.setProperty("nativePieceDispatchContractPassed", "true");
        report.setProperty("nativePieceStandaloneServerWorldUsed", "false");
        report.setProperty("nativePieceFixture", "isolated-air-input-not-complete-structure-stage");
        report.setProperty("nativePieceLegacyReference", "original-StructureStart-with-inactive-plugin-callback-test-double");
        report.setProperty("nativePieceResultsDiscarded", "true");
    }

    private static Result place(HeadlessTerrainContext context, IrisDimension dimension,
                    HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord> planned, boolean reference) {
        var structure = context.structures().registries().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:ancient_city"))).value();
        var original = planned.chunk().getStartForStructure(structure);
        var saved = original.createTag(context.serialization().structures(), planned.chunk().getPos());
        var start = StructureStart.loadStaticStart(context.serialization().structures(), saved.copy(), context.engine().getSeedManager().getSeed());
        if (start == null || !start.isValid()) throw new IllegalStateException("Native piece start did not restore");
        var centerBlock = start.getPieces().getFirst().getBoundingBox().getCenter();
        var center = new ChunkPos(centerBlock.getX() >> 4, centerBlock.getZ() >> 4);
        var fixture = NativeStructureBlockBoundary.fixture(context, dimension, center);
        var manager = new StructureManager(fixture.world(), context.structures().worldOptions(), null);
        var area = new BoundingBox(center.getMinBlockX(), context.engine().getMinHeight() + 1, center.getMinBlockZ(),
                center.getMaxBlockX(), context.engine().getMaxHeight() - 1, center.getMaxBlockZ());
        var random = NativeStructureBlockBoundary.decorationRandom(context, structure, center);
        if (reference) {
            // Constructor interception is test-only. No callback or server substitute enters the standalone adapter.
            try (var callbacks = mockConstruction(CraftStructureTransformer.class, (transformer, construction) -> {
                when(transformer.canTransformBlocks()).thenReturn(false);
                when(transformer.transformEntity(any())).thenReturn(true);
            })) {
                start.placeInChunk(fixture.world(), manager, context.structures().generator(), random, area, center);
                if (callbacks.constructed().size() != 1) throw new IllegalStateException("Original native callback path was not exercised");
                verify(callbacks.constructed().getFirst()).discard();
            }
        } else {
            NativePieceDispatcher.place(start, fixture.world(), manager, context.structures().generator(), random, area, center);
        }
        var hash = PackFingerprint.digest();
        var states = new HashSet<String>();
        int nonAir = 0;
        var position = new BlockPos.MutableBlockPos();
        for (int x = center.getMinBlockX(); x <= center.getMaxBlockX(); x++)
            for (int z = center.getMinBlockZ(); z <= center.getMaxBlockZ(); z++)
                for (int y = context.engine().getMinHeight(); y < context.engine().getMaxHeight(); y++) {
                    var state = fixture.world().getBlockState(position.set(x, y, z));
                    String key = state.toString();
                    states.add(key);
                    if (!state.isAir()) nonAir++;
                    hash.update(key.getBytes(StandardCharsets.UTF_8)); hash.update((byte) 0);
                }
        for (var chunk : fixture.chunks()) if (chunk.getPersistedStatus() != ChunkStatus.TERRAIN)
            throw new IllegalStateException("Isolated native piece results were promoted beyond TERRAIN");
        if (!saved.equals(original.createTag(context.serialization().structures(), planned.chunk().getPos())))
            throw new IllegalStateException("Native piece test changed original start NBT");
        NativeFixtureProof.requireNoServer();
        return new Result(nonAir, states.size(), HexFormat.of().formatHex(hash.digest()));
    }

    private static void dispatchContract() {
        var structure = mock(Structure.class);
        var first = mock(StructurePiece.class); var outside = mock(StructurePiece.class); var last = mock(StructurePiece.class);
        when(first.getBoundingBox()).thenReturn(new BoundingBox(-4, 20, -4, 4, 24, 4));
        when(outside.getBoundingBox()).thenReturn(new BoundingBox(128, 20, 128, 144, 24, 144));
        when(last.getBoundingBox()).thenReturn(new BoundingBox(1, 20, 1, 8, 24, 8));
        var pieces = List.of(first, outside, last);
        var start = new StructureStart(structure, new ChunkPos(0, 0), 0, new PiecesContainer(pieces));
        var world = mock(WorldGenLevel.class); var manager = mock(StructureManager.class); var generator = mock(ChunkGenerator.class);
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        var area = new BoundingBox(0, -255, 0, 15, 511, 15); var chunk = new ChunkPos(0, 0); var anchor = new BlockPos(0, 20, 0);
        NativePieceDispatcher.place(start, world, manager, generator, random, area, chunk);
        var order = inOrder(first, last, structure);
        order.verify(first).postProcess(world, manager, generator, random, area, chunk, anchor);
        order.verify(last).postProcess(world, manager, generator, random, area, chunk, anchor);
        var container = ArgumentCaptor.forClass(PiecesContainer.class);
        order.verify(structure).afterPlace(eq(world), eq(manager), eq(generator), eq(random), eq(area), eq(chunk), container.capture());
        if (!container.getValue().pieces().equals(pieces)) throw new IllegalStateException("Native afterPlace lost the complete piece list");
        verify(outside, never()).postProcess(any(), any(), any(), any(), any(), any(), any());
        NativePieceDispatcher.place(new StructureStart(structure, chunk, 0, new PiecesContainer(List.of())), world, manager, generator, random, area, chunk);
        verify(structure, times(1)).afterPlace(any(), any(), any(), any(), any(), any(), any());
    }
    private record Result(int nonAir, int states, String hash) {}
}
