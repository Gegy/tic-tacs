package net.gegy1000.tictacs;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

public interface NonBlockingChunkAccess {
    Chunk getExistingChunk(int x, int z, ChunkStatus status);

    default boolean doesChunkExist(int x, int z, ChunkStatus status) {
        return this.getExistingChunk(x, z, status) != null;
    }

    default boolean doesChunkExist(int x, int z) {
        return this.doesChunkExist(x, z, ChunkStatus.FULL);
    }

    default boolean doesChunkExist(BlockPos pos) {
        return this.doesChunkExist(pos.getX() >> 4, pos.getZ() >> 4);
    }
}
