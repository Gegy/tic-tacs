package net.gegy1000.acttwo.chunk.future;

import net.gegy1000.acttwo.chunk.ChunkContext;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

public final class GetChunkContext implements Future<List<Chunk>> {
    private final ThreadedAnvilChunkStorage tacs;

    private final ChunkPos focusChunk;
    private final ChunkStatus[] statuses;

    private ChunkStatus currentStatus;

    private Chunk[] currentChunks;
    private Future<Chunk>[] currentFutures;

    private int loadedChunks;

    public GetChunkContext(ThreadedAnvilChunkStorage tacs, ChunkPos focusChunk, ChunkStatus[] statuses) {
        this.tacs = tacs;
        this.focusChunk = focusChunk;
        this.statuses = statuses;

        this.currentStatus = statuses[0];
    }

    public void upgradeTo(ChunkStatus status) {
        ChunkStatus firstStatus = this.statuses[0];
        ChunkStatus finalStatus = this.statuses[this.statuses.length - 1];

        if (!(status.isAtLeast(firstStatus) && finalStatus.isAtLeast(status))) {
            throw new IllegalArgumentException(status + " not in range [" + firstStatus + ", " + finalStatus + "]");
        }

        this.currentStatus = status;
        this.currentChunks = null;
        this.currentFutures = null;
    }

    @Nullable
    @Override
    public List<Chunk> poll(Waker waker) {
        if (this.currentFutures == null) {
            this.currentChunks = this.createChunksFor(this.currentStatus);
            this.currentFutures = ChunkContext.forStatus(this.currentStatus).spawn(this.tacs, this.focusChunk);
            this.loadedChunks = 0;
        }

        if (this.pollChunks(this.currentStatus, this.currentFutures, this.currentChunks, waker)) {
            return Arrays.asList(this.currentChunks);
        }

        return null;
    }

    private boolean pollChunks(ChunkStatus status, Future<Chunk>[] futures, Chunk[] chunks, Waker waker) {
        int radius = status.getTaskMargin();
        int diameter = radius * 2 + 1;

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * diameter;

                Future<Chunk> future = futures[idx];
                if (future == null) continue;

                Chunk chunk = future.poll(waker);
                if (chunk != null) {
                    futures[idx] = null;
                    chunks[idx] = chunk;

                    this.loadedChunks++;
                }
            }
        }

        return this.loadedChunks >= futures.length;
    }

    private Chunk[] createChunksFor(ChunkStatus focusStatus) {
        int radius = focusStatus.getTaskMargin();
        int diameter = radius * 2 + 1;
        return new Chunk[diameter * diameter];
    }
}
