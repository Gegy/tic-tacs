package net.gegy1000.tictacs.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.future.SharedListener;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.Chunk;

import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CompletableFuture;

public final class ChunkListener extends SharedListener<Chunk> {
    final ChunkEntry entry;
    final ChunkStep step;

    volatile boolean err;

    final CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> vanilla;

    ChunkListener(ChunkEntry entry, ChunkStep step) {
        this.entry = entry;
        this.step = step;

        Chunk chunk = this.getChunkForStep();
        if (chunk != null) {
            this.vanilla = CompletableFuture.completedFuture(Either.left(chunk));
        } else {
            this.vanilla = new CompletableFuture<>();
        }
    }

    @Nullable
    @Override
    protected Chunk get() {
        if (this.err) {
            throw ChunkNotLoadedException.INSTANCE;
        }

        return this.getChunkForStep();
    }

    public void completeOk() {
        this.err = false;

        Chunk chunk = this.getChunkForStep();
        if (chunk == null) {
            throw new IllegalStateException("cannot complete chunk with null chunk");
        }

        this.vanilla.complete(Either.left(chunk));

        this.wake();
    }

    public void completeErr() {
        this.err = true;
        this.vanilla.complete(ChunkHolder.UNLOADED_CHUNK);

        this.wake();
    }

    private Chunk getChunkForStep() {
        return this.entry.getChunkForStep(this.step);
    }

    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> asVanilla() {
        return this.vanilla;
    }
}
