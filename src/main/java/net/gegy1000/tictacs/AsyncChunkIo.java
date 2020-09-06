package net.gegy1000.tictacs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.math.ChunkPos;

import java.util.concurrent.CompletableFuture;

public interface AsyncChunkIo {
    CompletableFuture<CompoundTag> getNbtAsync(ChunkPos pos);
}
