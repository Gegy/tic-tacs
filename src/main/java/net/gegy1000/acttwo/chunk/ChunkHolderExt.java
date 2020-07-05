package net.gegy1000.acttwo.chunk;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryListener;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

public interface ChunkHolderExt {
    void tryUpgradeTo(ThreadedAnvilChunkStorage tacs, ChunkStatus status);

    ChunkEntryListener getListenerFor(ChunkStatus status);

    void complete(ChunkStatus status, Either<Chunk, ChunkHolder.Unloaded> result);

    default void completeOk(ChunkStatus status, Chunk chunk) {
        this.complete(status, Either.left(chunk));
    }

    default void completeErr(ChunkStatus status, ChunkHolder.Unloaded err) {
        this.complete(status, Either.right(err));
    }
}
