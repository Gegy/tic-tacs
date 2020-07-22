package net.gegy1000.acttwo.chunk.worker;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.util.thread.ThreadExecutor;

import java.util.concurrent.atomic.AtomicBoolean;

// TODO: i generally don't like this solution. help
public final class ChunkMainThreadExecutor implements AutoCloseable, Runnable {
    private final ThreadExecutor<Runnable> executor;
    private final AtomicBoolean enqueued = new AtomicBoolean(false);

    private final ChunkTaskQueue queue = new ChunkTaskQueue();

    public ChunkMainThreadExecutor(ThreadExecutor<Runnable> executor) {
        this.executor = executor;
        this.queue.onNotify(this::tryEnqueue);
    }

    public <T> void spawn(ChunkHolder holder, Future<T> future) {
        ChunkTask<T> task = new ChunkTask<>(holder, future, this.queue);
        this.queue.enqueue(task);
    }

    @Override
    public void run() {
        this.enqueued.set(false);

        ChunkTask<?> task = this.queue.remove();
        if (task != null) {
            task.advance();

            // we still have more tasks to process: re-enqueue ourselves to the executor
            this.tryEnqueue();
        }
    }

    private void tryEnqueue() {
        if (this.enqueued.compareAndSet(false, true)) {
            this.executor.submit(this);
        }
    }

    @Override
    public void close() {
        this.queue.close();
    }
}
