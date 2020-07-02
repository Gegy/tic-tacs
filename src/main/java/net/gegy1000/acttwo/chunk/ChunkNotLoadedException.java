package net.gegy1000.acttwo.chunk;

import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;

public final class ChunkNotLoadedException extends RuntimeException {
    private final ChunkPos pos;

    public ChunkNotLoadedException() {
        this.pos = null;
    }

    public ChunkNotLoadedException(ChunkPos pos) {
        this.pos = pos;
    }

    @Nullable
    public ChunkPos getPos() {
        return this.pos;
    }
}
