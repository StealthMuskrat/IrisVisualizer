package dev.irisvisualizer.engine;

import art.arcane.iris.generation.runtime.Engine;
import art.arcane.iris.generation.runtime.IrisEngine;
import art.arcane.iris.platform.bukkit.nms.BukkitStructureStagePolicy;
import art.arcane.iris.structure.nativegen.NativeStructureOwnershipRecord;
import art.arcane.iris.structure.nativegen.NativeStructureOwnershipStore;
import art.arcane.iris.structure.nativegen.NativeStructureStartPlan;
import art.arcane.iris.generation.terrain.IrisDimension;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeBiomeSourceImpl;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeBukkitStructureStage;
import art.arcane.volmlib.nativelib.v26_3_R1.terrain.NativeStructureOwnershipRecovery;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the official native structure stage on caller-owned staging chunks, without creating history. */
public final class NativeStructureStage {
    private final Engine engine;
    private final StructureTemplateManager templates;
    private final BukkitStructureStagePolicy policy;
    private final NativeBukkitStructureStage<Engine, IrisDimension, NativeStructureStartPlan, NativeStructureOwnershipRecord> stage;

    public NativeStructureStage(Engine engine, ChunkGenerator generator, StructureTemplateManager templates) {
        this.engine = Objects.requireNonNull(engine);
        this.templates = Objects.requireNonNull(templates);
        if (!(generator.getBiomeSource() instanceof NativeBiomeSourceImpl biomes))
            throw new IllegalArgumentException("Native structure stage requires the official native biome source");
        policy = new BukkitStructureStagePolicy(engine);
        stage = new NativeBukkitStructureStage<>(new NativeBukkitStructureStage.Configuration<>(generator, biomes,
                engine.getMinHeight(), policy, policy, new NamespacedKey("iris", "structure_activation")));
    }

    public Result place(WorldGenLevel world, ChunkAccess chunk, StructureManager structures) {
        if (engine instanceof IrisEngine iris && iris.getGenerationHistoryRuntimeRouter().isPresent())
            throw new IllegalStateException("Standalone structure stage must not attach generation history");
        if (world.getSeed() != engine.getSeedManager().getSeed() || world.getMinY() != engine.getMinHeight()
                || world.getHeight() != engine.getHeight() || chunk.getMinY() != engine.getMinHeight()
                || chunk.getHeight() != engine.getHeight())
            throw new IllegalArgumentException("Native stage input differs from its engine seed or configured height");
        if (chunk.getPersistedStatus() != ChunkStatus.TERRAIN)
            throw new IllegalArgumentException("Native structure stage requires staging chunks at TERRAIN status");
        AtomicInteger palettes = new AtomicInteger();
        stage.placeStandaloneStructures(world, chunk, structures, new NativeBukkitStructureStage.StandaloneServices<>(
                this::ownership, () -> templates, NativePieceDispatcher::place,
                (source, random, x, y, z) -> {
                    palettes.incrementAndGet();
                    var state = policy.paletteBlock(source, random, x, y, z);
                    if (!(state instanceof ModdedBlockState nativeState))
                        throw new IllegalStateException("Structure palette did not resolve an actual native block state");
                    return nativeState.handle();
                }));
        return new Result(palettes.get());
    }

    private NativeStructureOwnershipRecord ownership(String key, Structure structure, StructureStart start) {
        var origin = start.getChunkPos();
        var record = NativeStructureOwnershipStore.findPersisted(engine, key, origin.x(), origin.z());
        if (record == null) {
            if (policy.structurePolicy(engine).matchingPlan(key, origin.x(), origin.z()) != null)
                throw new IllegalStateException("Configured native start lacks current-session ownership: " + key);
            return null;
        }
        var refreshed = NativeStructureOwnershipRecovery.refreshReferenceEnvelope(key, structure, start, record);
        if (refreshed == null) throw new IllegalStateException("Native start ownership does not match its planned pieces: " + key);
        if (refreshed != record) NativeStructureOwnershipStore.record(engine, refreshed);
        return refreshed;
    }
    public record Result(int paletteLookups) {}
}
