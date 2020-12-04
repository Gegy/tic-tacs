package net.gegy1000.tictacs.chunk.future;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.AtomicPool;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.Chunk;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class VanillaChunkFuture implements Future<Chunk> {
    private static final AtomicPool<VanillaChunkFuture> POOL = new AtomicPool<>(512, VanillaChunkFuture::new);

    private volatile CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> inner;
    private volatile boolean listening;

    private VanillaChunkFuture() {
    }

    public static VanillaChunkFuture of(CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> completable) {
        VanillaChunkFuture future = POOL.acquire();
        future.init(completable);
        return future;
    }

    void init(CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future) {
        this.inner = future;
        this.listening = false;
    }

    @Nullable
    @Override
    public Chunk poll(Waker waker) {
        Either<Chunk, ChunkHolder.Unloaded> result = this.inner.getNow(null);
        if (result != null) {
            Optional<ChunkHolder.Unloaded> err = result.right();
            if (err.isPresent()) {
                throw ChunkNotLoadedException.INSTANCE;
            }

            Chunk chunk = result.left().get();
            this.release();

            return chunk;
        } else if (!this.listening) {
            this.listening = true;
            this.inner.handle((r, t) -> {
                waker.wake();
                return null;
            });
        }

        return null;
    }

    private void release() {
        this.inner = null;
        POOL.release(this);
    }
}
