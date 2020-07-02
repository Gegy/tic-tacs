package net.gegy1000.acttwo.chunk;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.CompletableFuture;

public interface ChunkHolderExt {
    AsyncChunkState getAsyncState();

    void combineFuture(CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future);
}
