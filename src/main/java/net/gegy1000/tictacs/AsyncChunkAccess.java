package net.gegy1000.tictacs;

import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.CompletableFuture;

public interface AsyncChunkAccess {
    Chunk getExistingChunk(int x, int z, ChunkStep step);

    Chunk getAnyExistingChunk(int x, int z);

    CompletableFuture<Chunk> getOrCreateChunkAsync(int x, int z, ChunkStep step);

    boolean shouldChunkExist(int x, int z, ChunkStep step);

    default boolean shouldChunkExist(int x, int z) {
        return this.shouldChunkExist(x, z, ChunkStep.FULL);
    }
}
