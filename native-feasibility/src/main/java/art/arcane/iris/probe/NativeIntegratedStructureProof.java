package art.arcane.iris.probe;

import art.arcane.iris.generation.runtime.Engine;
import art.arcane.iris.generation.runtime.IrisEngine;
import art.arcane.iris.generation.terrain.IrisDimension;
import art.arcane.iris.platform.bukkit.nms.BukkitStructureStagePolicy;
import art.arcane.iris.structure.nativegen.NativeStructureOwnershipRecord;
import art.arcane.iris.structure.nativegen.NativeStructureStartPlan;
import art.arcane.volmlib.nativelib.terrain.NativeBiome;
import art.arcane.volmlib.nativelib.terrain.NativeBlockState;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeBiomeSourceImpl;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeBukkitStructureStage;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeStructurePostProcessor;
import art.arcane.volmlib.util.hunk.Hunk;
import dev.irisvisualizer.engine.NativeStructureStage;
import dev.irisvisualizer.feasibility.PackFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.util.CraftStructureTransformer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

/** Integrated native-stage comparisons on both actual generated terrain and targeted stilt inputs. */
final class NativeIntegratedStructureProof {
    private NativeIntegratedStructureProof() {}

    static void run(HeadlessTerrainContext context, IrisDimension dimension,
                    List<HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord>> origins,
                    Path output, Properties report) throws Exception {
        if (!(context.engine() instanceof IrisEngine engine)) throw new IllegalArgumentException("Real Iris engine required");
        report.setProperty("terrainGenerationAttempted", "true");
        int cases = 0, generated = 0, stiltCases = 0;
        try (var writer = Files.newBufferedWriter(output.resolve("integrated-native-structures.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("originChunkX\toriginChunkZ\tinput\tchunks\tminY\theight\tpaletteLookups\tnonAirBlocks\tblockStates\tbeforeSha256\tstandaloneSha256\tlegacySha256\tbiomesSha256\n");
            for (var origin : origins) {
                var city = city(context);
                var original = origin.chunk().getStartForStructure(city);
                var centerBlock = original.getPieces().getFirst().getBoundingBox().getCenter();
                var center = new ChunkPos(centerBlock.getX() >> 4, centerBlock.getZ() >> 4);
                List<Terrain> terrain = generateTerrain(context, center);
                generated += terrain.size();
                for (boolean stilt : List.of(false, true)) {
                    var standalone = execute(context, dimension, origin, terrain, stilt, false, false);
                    var legacy = execute(context, dimension, origin, terrain, stilt, true, false);
                    if (!standalone.before().equals(legacy.before()) || !standalone.after().equals(legacy.after()) || standalone.paletteLookups() != legacy.paletteLookups()
                            || standalone.before().blocks().equals(standalone.after().blocks())
                            || !standalone.before().biomes().equals(standalone.after().biomes()))
                        throw new IllegalStateException("Integrated native structure output or biome preservation differs");
                    if (stilt) {
                        if (standalone.paletteLookups() == 0) throw new IllegalStateException("Stilt fixture did not exercise the pack's native palette");
                        stiltCases++;
                    }
                    String input = stilt ? "stilt-gap-fixture" : "official-Iris-generated-terrain";
                    writer.write(origin.chunk().getPos().x() + "\t" + origin.chunk().getPos().z() + "\t" + input + "\t9\t"
                            + engine.getMinHeight() + "\t" + engine.getHeight() + "\t" + standalone.paletteLookups() + "\t"
                            + standalone.after().nonAir() + "\t" + standalone.after().states() + "\t" + standalone.before().blocks()
                            + "\t" + standalone.after().blocks() + "\t" + legacy.after().blocks() + "\t" + standalone.after().biomes() + "\n");
                    writer.flush();
                    System.out.println("[integrated-structures] origin=" + origin.chunk().getPos().x() + "," + origin.chunk().getPos().z()
                            + " input=" + input + " chunks=9 paletteLookups=" + standalone.paletteLookups()
                            + " blockAndBiomeHashesMatch=true standaloneServerWorldUsed=false");
                    cases++;
                }
                execute(context, dimension, origin, terrain, true, false, true);
                NativeFixtureProof.requireNoReports(engine);
            }
        }
        report.setProperty("integratedNativeStructureStagePassed", "true");
        report.setProperty("structureBlocksPlaced", "true");
        report.setProperty("integratedNativeStructureCases", Integer.toString(cases));
        report.setProperty("integratedGeneratedTerrainChunks", Integer.toString(generated));
        report.setProperty("integratedNativeStiltCases", Integer.toString(stiltCases));
        report.setProperty("integratedNativeHashesMatch", "true");
        report.setProperty("integratedNativeBiomesPreserved", "true");
        report.setProperty("integratedFailureInvalidationPassed", "true");
        report.setProperty("integratedResultsDiscarded", "true");
        report.setProperty("integratedScope", "explicit-ancient-city-stage-not-all-world-structures-or-features");
        NativeFixtureProof.requireNoServer();
    }

    private static List<Terrain> generateTerrain(HeadlessTerrainContext context, ChunkPos center) throws Exception {
        List<Terrain> result = new ArrayList<>();
        for (int x = center.x() - 1; x <= center.x() + 1; x++) for (int z = center.z() - 1; z <= center.z() + 1; z++) {
            var blocks = Hunk.<NativeBlockState>newArrayHunk(16, context.engine().getHeight(), 16);
            var biomes = Hunk.<NativeBiome>newArrayHunk(16, context.engine().getHeight(), 16);
            context.engine().generate(x << 4, z << 4, blocks, biomes, true);
            result.add(new Terrain(x, z, blocks, biomes));
            System.out.println("[integrated-structures] generated full-height native terrain chunk=" + x + "," + z);
        }
        return List.copyOf(result);
    }

    private static Result execute(HeadlessTerrainContext context, IrisDimension dimension,
                    HeadlessStructurePlanner.PlannedChunk<NativeStructureOwnershipRecord> origin, List<Terrain> terrain,
                    boolean stilt, boolean reference, boolean failDispatch) throws Exception {
        var structure = city(context);
        var original = origin.chunk().getStartForStructure(structure);
        var tag = original.createTag(context.serialization().structures(), origin.chunk().getPos());
        var start = StructureStart.loadStaticStart(context.serialization().structures(), tag.copy(), context.engine().getSeedManager().getSeed());
        if (start == null || !start.isValid()) throw new IllegalStateException("Integrated start did not restore");
        var centerBlock = start.getPieces().getFirst().getBoundingBox().getCenter();
        var center = new ChunkPos(centerBlock.getX() >> 4, centerBlock.getZ() >> 4);
        var fixture = NativeStructureBlockBoundary.fixture(context, dimension, center);
        for (var chunk : fixture.chunks()) {
            if (stilt) {
                var pos = new BlockPos.MutableBlockPos();
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                    chunk.setBlockState(pos.set(chunk.getPos().getMinBlockX() + x, chunk.getMinY() + 2,
                            chunk.getPos().getMinBlockZ() + z), Blocks.STONE.defaultBlockState(), 2);
            } else {
                var input = terrain.stream().filter(value -> value.x() == chunk.getPos().x() && value.z() == chunk.getPos().z()).findFirst().orElseThrow();
                context.writer().fill(chunk, new NativeRegionTerrainWriter.TerrainInput(input.x(), input.z(), context.engine().getMinHeight(),
                        context.engine().getHeight(), (x, y, z) -> nativeState(input.blocks().getRaw(x, y, z)),
                        (x, y, z) -> Objects.requireNonNull(input.biomes().getRaw(x, y, z), "Native generated biome").key(), List.of()));
            }
        }
        var target = fixture.chunks().stream().filter(chunk -> chunk.getPos().equals(center)).findFirst().orElseThrow();
        var manager = mock(StructureManager.class);
        when(manager.shouldGenerateStructures()).thenReturn(true);
        when(manager.startsForStructure(anyInt(), anyInt(), any(Structure.class))).thenAnswer(call ->
                call.getArgument(2) == structure ? List.of(start) : List.of());
        Snapshot before = snapshot(context, fixture.chunks());
        int palettes;
        if (!reference && !failDispatch) {
            palettes = new NativeStructureStage(context.engine(), context.structures().generator(), context.structures().templates())
                    .place(fixture.world(), target, manager).paletteLookups();
        } else {
            var basePolicy = new BukkitStructureStagePolicy(context.engine());
            var policy = spy(basePolicy);
            AtomicInteger paletteCalls = new AtomicInteger();
            Map<BlockState, NativeBlockState> handles = new IdentityHashMap<>();
            doAnswer(call -> {
                paletteCalls.incrementAndGet();
                var nativeBlock = basePolicy.paletteBlock(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4));
                if (!(nativeBlock instanceof ModdedBlockState actual)) throw new IllegalStateException("Non-native palette state");
                return handles.computeIfAbsent(actual.handle(), state -> {
                    var handle = mock(NativeBlockState.class);
                    when(handle.placementHandle()).thenReturn(CraftBlockData.fromData(state));
                    return handle;
                });
            }).when(policy).paletteBlock(any(), any(), anyInt(), anyInt(), anyInt());
            var stage = new NativeBukkitStructureStage<>(new NativeBukkitStructureStage.Configuration<>(context.structures().generator(),
                    (NativeBiomeSourceImpl) context.structures().generator().getBiomeSource(), context.engine().getMinHeight(),
                    policy, policy, new NamespacedKey("iris", "structure_activation")));
            if (failDispatch) {
                var ownership = origin.ownership().get(structure);
                boolean rejected = false;
                try {
                    stage.placeStandaloneStructures(fixture.world(), target, manager, new NativeBukkitStructureStage.StandaloneServices<>(
                            (key, selected, pieces) -> ownership, () -> context.structures().templates(),
                            (pieces, world, structures, generator, random, area, position) -> { throw new IllegalStateException("injected-stage-failure"); },
                            (source, random, x, y, z) -> nativeState(basePolicy.paletteBlock(source, random, x, y, z))));
                } catch (RuntimeException failure) {
                    for (Throwable cause = failure; cause != null; cause = cause.getCause())
                        if ("injected-stage-failure".equals(cause.getMessage())) rejected = true;
                    if (!rejected) throw failure;
                }
                if (!rejected) throw new IllegalStateException("Injected stage failure was swallowed");
            } else {
                var level = mock(ServerLevel.class);
                when(level.getStructureTemplateManager()).thenReturn(context.structures().templates());
                WorldGenLevel legacy = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getLevel")) return level;
                            try { return method.invoke(fixture.world(), args); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
                try (var callbacks = mockConstruction(CraftStructureTransformer.class, (transformer, construction) -> {
                    when(transformer.canTransformBlocks()).thenReturn(false);
                    when(transformer.transformEntity(any())).thenReturn(true);
                })) {
                    stage.placeVanillaStructures(legacy, target, manager);
                    if (callbacks.constructed().size() != 1) throw new IllegalStateException("Legacy full-stage callback reference was not exercised");
                    verify(callbacks.constructed().getFirst()).discard();
                }
                verify(level, atLeastOnce()).getStructureTemplateManager();
                verifyNoMoreInteractions(level);
            }
            verify(policy, times(1)).invalidateBlockChanges(eq(context.engine()), any());
            palettes = paletteCalls.get();
        }
        for (var chunk : fixture.chunks()) if (chunk.getPersistedStatus() != ChunkStatus.TERRAIN)
            throw new IllegalStateException("Integrated partial world was promoted beyond TERRAIN");
        if (!tag.equals(original.createTag(context.serialization().structures(), origin.chunk().getPos())))
            throw new IllegalStateException("Integrated stage changed original start NBT");
        NativeFixtureProof.requireNoServer();
        return new Result(before, snapshot(context, fixture.chunks()), palettes);
    }

    private static Structure city(HeadlessTerrainContext context) {
        return context.structures().registries().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:ancient_city"))).value();
    }
    private static BlockState nativeState(NativeBlockState state) {
        if (state == null) return Blocks.AIR.defaultBlockState();
        if (!(state instanceof ModdedBlockState nativeState)) throw new IllegalStateException("Generated state is not native");
        return nativeState.handle();
    }
    private static Snapshot snapshot(HeadlessTerrainContext context, List<ProtoChunk> chunks) {
        var blocks = PackFingerprint.digest(); var biomes = PackFingerprint.digest();
        Map<BlockState, byte[]> keys = new IdentityHashMap<>();
        var states = new HashSet<BlockState>();
        var registry = context.structures().registries().lookupOrThrow(Registries.BIOME);
        int nonAir = 0; var pos = new BlockPos.MutableBlockPos();
        for (var chunk : chunks) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = chunk.getMinY(); y < chunk.getMinY() + chunk.getHeight(); y++) {
                var state = chunk.getBlockState(pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z));
                states.add(state); if (!state.isAir()) nonAir++;
                blocks.update(keys.computeIfAbsent(state, value -> value.toString().getBytes(StandardCharsets.UTF_8))); blocks.update((byte) 0);
            }
            for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) for (int y = chunk.getMinY() >> 2; y < (chunk.getMinY() + chunk.getHeight()) >> 2; y++) {
                var biome = chunk.getNoiseBiome((chunk.getPos().x() << 2) + x, y, (chunk.getPos().z() << 2) + z);
                biomes.update(Objects.requireNonNull(registry.getKey(biome.value())).toString().getBytes(StandardCharsets.UTF_8)); biomes.update((byte) 0);
            }
        }
        return new Snapshot(HexFormat.of().formatHex(blocks.digest()), HexFormat.of().formatHex(biomes.digest()), nonAir, states.size());
    }
    private record Terrain(int x, int z, Hunk<NativeBlockState> blocks, Hunk<NativeBiome> biomes) {}
    private record Snapshot(String blocks, String biomes, int nonAir, int states) {}
    private record Result(Snapshot before, Snapshot after, int paletteLookups) {}
}
