package dev.irisvisualizer.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

/** Native piece dispatch for a standalone host with no Bukkit plugin-event system. */
public final class NativePieceDispatcher {
    private NativePieceDispatcher() {}

    public static void place(StructureStart start, WorldGenLevel world, StructureManager structures,
                             ChunkGenerator generator, WorldgenRandom random, BoundingBox area, ChunkPos chunk) {
        var pieces = start.getPieces();
        if (pieces.isEmpty()) return;
        var firstBounds = pieces.getFirst().getBoundingBox();
        var center = firstBounds.getCenter();
        var anchor = new BlockPos(center.getX(), firstBounds.minY(), center.getZ());
        for (var piece : pieces) {
            if (piece.getBoundingBox().intersects(area)) {
                piece.postProcess(world, structures, generator, random, area, chunk, anchor);
            }
        }
        start.getStructure().afterPlace(world, structures, generator, random, area, chunk, new PiecesContainer(pieces));
    }
}
