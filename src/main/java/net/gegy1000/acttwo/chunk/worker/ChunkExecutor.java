package net.gegy1000.acttwo.chunk.worker;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;

public final class ChunkExecutor implements AutoCloseable {
    private final ChunkTaskQueue queue = new ChunkTaskQueue();

    public <T> void spawn(ChunkHolder holder, Future<T> future) {
        ChunkTask<T> task = new ChunkTask<>(holder, future, this.queue);
        this.queue.enqueue(task);
    }

    public void run() throws InterruptedException {
        ChunkTask<?> task;
        while ((task = this.queue.take()) != null) {
            task.advance();
        }
    }

    @Override
    public void close() {
        this.queue.close();
    }
}
