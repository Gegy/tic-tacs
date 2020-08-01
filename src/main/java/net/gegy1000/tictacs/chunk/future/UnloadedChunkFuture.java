package net.gegy1000.tictacs.chunk.future;

import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

public final class UnloadedChunkFuture implements Future<Chunk> {
    public static final UnloadedChunkFuture INSTANCE = new UnloadedChunkFuture();

    private UnloadedChunkFuture() {
    }

    @Nullable
    @Override
    public Chunk poll(Waker waker) {
        throw ChunkNotLoadedException.INSTANCE;
    }
}
