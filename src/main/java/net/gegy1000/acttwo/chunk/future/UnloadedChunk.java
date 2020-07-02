package net.gegy1000.acttwo.chunk.future;

import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

public final class UnloadedChunk implements Future<Chunk> {
    public static final UnloadedChunk INSTANCE = new UnloadedChunk();

    private UnloadedChunk() {
    }

    @Nullable
    @Override
    public Chunk poll(Waker waker) {
        throw new ChunkNotLoadedException();
    }
}
