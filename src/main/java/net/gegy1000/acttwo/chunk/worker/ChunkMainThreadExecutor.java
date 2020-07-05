package net.gegy1000.acttwo.chunk.worker;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.util.thread.ThreadExecutor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkMainThreadExecutor implements AutoCloseable {
    private final ThreadExecutor<Runnable> executor;
    private final AtomicBoolean enqueued = new AtomicBoolean();

    private final ChunkTaskQueue queue = new ChunkTaskQueue();

    public ChunkMainThreadExecutor(ThreadExecutor<Runnable> executor) {
        this.executor = executor;
    }

    public <T> void spawn(ChunkHolder holder, Future<T> future) {
        ChunkTask<T> task = new ChunkTask<>(holder, future, this.queue);
        this.queue.enqueue(task);

        // enqueue ourselves to the executor if we aren't already
        this.tryEnqueue();
    }

    public boolean tryAdvance() {
        this.enqueued.set(false);

        List<ChunkTask<?>> queue = this.queue.remove();
        if (queue == null || queue.isEmpty()) {
            return false;
        }

        // TODO: if there's a lot of tasks, we could potentially block the main thread
        for (ChunkTask<?> task : queue) {
            task.advance();
        }

        // we still have more tasks to process: re-enqueue ourselves to the executor
        this.tryEnqueue();

        return true;
    }

    private void tryEnqueue() {
        if (this.enqueued.compareAndSet(false, true)) {
            this.executor.submit(this::tryAdvance);
        }
    }

    @Override
    public void close() {
        this.queue.close();
    }
}
