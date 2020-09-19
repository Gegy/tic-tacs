package net.gegy1000.tictacs.async.worker;

import net.gegy1000.justnow.future.Future;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.util.thread.ThreadExecutor;

import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkMainThreadExecutor implements TaskSpawner, TaskQueue, AutoCloseable, Runnable {
    private static final int BUFFER_SIZE = 16;

    private final ThreadExecutor<Runnable> executor;
    private final AtomicInteger enqueued = new AtomicInteger(0);

    private final LevelPrioritisedQueue<ChunkTask<?>> queue = new LevelPrioritisedQueue<>(ChunkLevelTracker.MAX_LEVEL);

    public ChunkMainThreadExecutor(ThreadExecutor<Runnable> executor) {
        this.executor = executor;
    }

    @Override
    public <T> void enqueue(ChunkTask<T> task) {
        this.queue.enqueue(task, task.getLevel());
        this.tryEnqueue();
    }

    @Override
    public <T> ChunkTask<T> spawn(ChunkEntry entry, Future<T> future) {
        ChunkTask<T> task = new ChunkTask<>(entry, future, this);
        this.enqueue(task);
        return task;
    }

    @Override
    public void run() {
        this.enqueued.getAndDecrement();

        ChunkTask<?> task = this.queue.remove();
        if (task != null) {
            task.advance();

            // we still have more tasks to process: re-enqueue ourselves to the executor
            this.tryEnqueue();
        }
    }

    private void tryEnqueue() {
        while (true) {
            int enqueued = this.enqueued.get();
            if (enqueued >= BUFFER_SIZE) {
                return;
            }

            if (this.enqueued.compareAndSet(enqueued, enqueued + 1)) {
                this.executor.submit(this);
                return;
            }
        }
    }

    @Override
    public void close() {
        this.queue.close();
    }
}
