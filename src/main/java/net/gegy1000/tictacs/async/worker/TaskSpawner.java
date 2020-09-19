package net.gegy1000.tictacs.async.worker;

import net.gegy1000.justnow.future.Future;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;

public interface TaskSpawner {
    <T> ChunkTask<T> spawn(ChunkEntry entry, Future<T> future);

    default <T> ChunkTask<T> spawn(Future<T> future) {
        return this.spawn(null, future);
    }
}
