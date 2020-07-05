package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.loader.ChunkLoader;
import net.gegy1000.acttwo.lock.RwGuard;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

// TODO: we're not releasing lock guards when exceptions are thrown
public final class GetChunkContextFuture implements Future<RwGuard<List<Chunk>>> {
    private final ChunkLoader chunkLoader;

    private final ChunkPos focusChunk;

    private ChunkContext currentContext;

    private RwGuard<Chunk>[] currentChunks;
    private Future<RwGuard<Chunk>>[] currentFutures;

    private int loadedChunks;

    public GetChunkContextFuture(ChunkLoader chunkLoader, ChunkPos focusChunk, ChunkContext context) {
        this.chunkLoader = chunkLoader;
        this.focusChunk = focusChunk;
        this.currentContext = context;
    }

    public void upgradeTo(ChunkContext context) {
        this.currentContext = context;
        this.currentChunks = null;
        this.currentFutures = null;
    }

    @Nullable
    @Override
    public RwGuard<List<Chunk>> poll(Waker waker) {
        if (this.currentFutures == null) {
            this.currentFutures = this.currentContext.spawn(this.chunkLoader, this.focusChunk);
            this.currentChunks = new RwGuard[this.currentFutures.length];
            this.loadedChunks = 0;
        }

        if (this.pollChunks(this.currentFutures, this.currentChunks, waker)) {
            return this.createGuard(this.currentChunks);
        }

        return null;
    }

    private RwGuard<List<Chunk>> createGuard(RwGuard<Chunk>[] chunkGuards) {
        List<Chunk> chunks = new ArrayList<>(chunkGuards.length);

        return new RwGuard<List<Chunk>>() {
            @Override
            public List<Chunk> get() {
                return chunks;
            }

            @Override
            public void release() {
                for (RwGuard<Chunk> chunk : chunkGuards) {
                    chunk.release();
                }
            }
        };
    }

    private boolean pollChunks(Future<RwGuard<Chunk>>[] futures, RwGuard<Chunk>[] chunks, Waker waker) {
        for (int i = 0; i < futures.length; i++) {
            Future<RwGuard<Chunk>> future = futures[i];
            if (future == null) continue;

            RwGuard<Chunk> chunk = future.poll(waker);
            if (chunk != null) {
                futures[i] = null;
                chunks[i] = chunk;

                this.loadedChunks++;
            }
        }

        return this.loadedChunks >= futures.length;
    }
}
