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
    volatile Throwable err;

    CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> vanilla = new CompletableFuture<>();

    @Nullable
    @Override
    protected Chunk get() {
        if (this.err != null) {
            throw encodeException(this.err);
        }

        return this.ok;
    }

    public void completeOk(Chunk chunk) {
        this.ok = chunk;
        this.vanilla.complete(Either.left(chunk));

        this.wake();
    }

    public void completeErr(ChunkNotLoadedException exception) {
        if (exception == null) {
            throw new IllegalArgumentException("cannot complete with null exception");
        }

        this.err = exception;
        this.vanilla.complete(ChunkHolder.UNLOADED_CHUNK);

        this.wake();
    }

    private static RuntimeException encodeException(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            return (RuntimeException) throwable;
        } else {
            return new RuntimeException(throwable);
        }
    }

    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> asVanilla() {
        return this.vanilla;
    }
}
