package net.gegy1000.tictacs.chunk.future;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;

import org.jetbrains.annotations.Nullable;

public final class ChunkNotLoadedFuture<T> implements Future<T> {
    private static final ChunkNotLoadedFuture<?> INSTANCE = new ChunkNotLoadedFuture<>();

    private ChunkNotLoadedFuture() {
    }

    @SuppressWarnings("unchecked")
    public static <T> ChunkNotLoadedFuture<T> get() {
        return (ChunkNotLoadedFuture<T>) INSTANCE;
    }

    @Nullable
    @Override
    public T poll(Waker waker) {
        throw ChunkNotLoadedException.INSTANCE;
    }
}
