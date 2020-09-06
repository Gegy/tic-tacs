package net.gegy1000.tictacs;

import net.minecraft.util.math.ChunkPos;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface AsyncRegionStorageIo {
    CompletableFuture<Void> loadDataAtAsync(ChunkPos pos, Executor mainThreadExecutor);
}
