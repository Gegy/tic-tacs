package net.gegy1000.tictacs.async.worker;

public interface TaskQueue {
    <T> void enqueue(ChunkTask<T> task);
}
