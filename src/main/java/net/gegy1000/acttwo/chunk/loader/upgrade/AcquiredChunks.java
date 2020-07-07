package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.justnow.future.Future;

import javax.annotation.Nullable;
import java.util.function.Function;

final class AcquiredChunks {
    private final ChunkUpgradeKernel kernel;

    final ChunkEntryState[] writerEntries;
    final ChunkEntryState[] readerEntries;

    AcquiredChunks(ChunkUpgradeKernel kernel) {
        this.kernel = kernel;

        int size = kernel.getSize();
        this.writerEntries = new ChunkEntryState[size * size];
        this.readerEntries = new ChunkEntryState[size * size];
    }

    public <T> void openWriteTasks(Future<T>[] tasks, Function<ChunkEntryState, Future<T>> function) {
        ChunkEntryState[] entries = this.writerEntries;

        for (int i = 0; i < entries.length; i++) {
            ChunkEntryState entry = entries[i];
            if (entry == null) continue;

            tasks[i] = function.apply(entry);
        }
    }

    public int getRadius() {
        return this.kernel.getRadius();
    }

    @Nullable
    public ChunkEntryState getEntry(int x, int z) {
        int idx = this.kernel.index(x, z);

        ChunkEntryState entry = this.writerEntries[idx];
        if (entry == null) {
            entry = this.readerEntries[idx];
        }

        return entry;
    }
}
