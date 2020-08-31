package net.gegy1000.tictacs.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.SharedListener;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class ChunkListener extends SharedListener<Chunk> {
    volatile Chunk ok;
    volatile boolean err;

    final CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> vanilla = new CompletableFuture<>();

    @Nullable
    @Override
    protected Chunk get() {
        if (this.err) {
            throw ChunkNotLoadedException.INSTANCE;
        }

        return this.ok;
    }

    public void completeOk(Chunk chunk) {
        this.ok = chunk;
        this.err = false;

        this.vanilla.complete(Either.left(chunk));

        this.wake();
    }

    public void completeErr() {
        this.ok = null;
        this.err = true;

        this.vanilla.complete(ChunkHolder.UNLOADED_CHUNK);

        this.wake();
    }

    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> asVanilla() {
        return this.vanilla;
    }
}
