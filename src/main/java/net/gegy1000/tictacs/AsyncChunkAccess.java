package net.gegy1000.tictacs;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

public interface AsyncChunkAccess {
    Chunk getExistingChunk(int x, int z, ChunkStatus status);

    boolean shouldChunkExist(int x, int z, ChunkStatus status);
}
