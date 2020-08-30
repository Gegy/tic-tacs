package net.gegy1000.tictacs;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.concurrent.CompletableFuture;

public interface AsyncChunkAccess {
    CompletableFuture<Chunk> getChunkAsync(int x, int z, ChunkStatus status);
}
