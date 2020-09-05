package net.gegy1000.tictacs.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.SharedListener;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class ChunkListener extends SharedListener<Chunk> {
    final ChunkEntryState entry;
    final ChunkStep step;

    volatile boolean err;

    final CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> vanilla = new CompletableFuture<>();

    ChunkListener(ChunkEntry entry, ChunkStep step) {
        this.entry = entry.getState();
        this.step = step;
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
        this.vanilla.complete(Either.left(this.getChunkForStep()));

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
