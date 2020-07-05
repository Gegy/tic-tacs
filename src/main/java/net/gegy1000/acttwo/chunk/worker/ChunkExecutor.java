package net.gegy1000.acttwo.chunk.worker;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;

import java.util.List;

public final class ChunkExecutor implements AutoCloseable {
    private final ChunkTaskQueue queue = new ChunkTaskQueue();

    public <T> void spawn(ChunkHolder holder, Future<T> future) {
        ChunkTask<T> task = new ChunkTask<>(holder, future, this.queue);
        this.queue.enqueue(task);
    }

    public void run() throws InterruptedException {
        List<ChunkTask<?>> queue;
        while ((queue = this.queue.take()) != null) {
            for (ChunkTask<?> task : queue) {
                task.advance();
            }
        }
    }

    @Override
    public void close() {
        this.queue.close();
    }
}
