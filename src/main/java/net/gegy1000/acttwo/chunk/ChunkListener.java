package net.gegy1000.acttwo.chunk;

import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ChunkListener implements Future<Chunk> {
    public final CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> completable = new CompletableFuture<>();

    Chunk result;
    Throwable exception;

    ReferenceSet<Waker> wakers;

    @Nullable
    @Override
    public synchronized Chunk poll(Waker waker) {
        this.registerWaker(waker);

        if (this.exception != null) {
            throw encodeException(this.exception);
        }

        return this.result;
    }

    public void complete(Either<Chunk, ChunkHolder.Unloaded> result) {
        Optional<ChunkHolder.Unloaded> err = result.right();
        if (err.isPresent()) {
            this.completeErr(new ChunkNotLoadedException());
        } else {
            this.completeOk(result.left().get());
        }
    }

    public synchronized void completeOk(Chunk result) {
        if (result == null) {
            throw new IllegalArgumentException("cannot complete with null result");
        }

        this.result = result;
        this.completable.complete(Either.left(result));

        this.wake();
    }

    public synchronized void completeErr(ChunkNotLoadedException exception) {
        if (exception == null) {
            throw new IllegalArgumentException("cannot complete with null exception");
        }

        this.exception = exception;
        this.completable.complete(Either.right(ChunkHolder.Unloaded.INSTANCE));

        this.wake();
    }

    private void registerWaker(Waker waker) {
        if (this.wakers == null) {
            this.wakers = new ReferenceOpenHashSet<>(2);
        }
        this.wakers.add(waker);
    }

    private void wake() {
        Set<Waker> wakers = this.wakers;
        this.wakers = null;

        if (wakers != null) {
            for (Waker waker : wakers) {
                waker.wake();
            }
        }
    }

    private static RuntimeException encodeException(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            return (RuntimeException) throwable;
        } else {
            return new RuntimeException(throwable);
        }
    }
}
