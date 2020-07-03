package net.gegy1000.acttwo.chunk.worker;

import net.minecraft.server.world.ThreadedAnvilChunkStorage;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkTaskQueue implements AutoCloseable {
    private static final int LEVEL_COUNT = ThreadedAnvilChunkStorage.MAX_LEVEL + 2;

    private final Level[] levels;
    private volatile int minLevel;

    private volatile boolean open = true;

    private final Object lock = new Object();

    public ChunkTaskQueue() {
        this.levels = new Level[LEVEL_COUNT];
        for (int i = 0; i < LEVEL_COUNT; i++) {
            this.levels[i] = new Level();
        }

        this.minLevel = LEVEL_COUNT;
    }

    public void enqueue(ChunkTask<?> task) {
        int level = task.holder.getLevel();
        if (level > ThreadedAnvilChunkStorage.MAX_LEVEL) {
            return;
        }

        synchronized (this.lock) {
            this.levels[level].enqueue(task);

            if (level <= this.minLevel) {
                this.minLevel = level;
                this.lock.notify();
            }
        }
    }

    // TODO: can we make this faster / reduce queue allocation?
    //         we can make use of LockSupport.park/unpark and atomics and avoid the lock

    @Nullable
    public List<ChunkTask<?>> take() throws InterruptedException {
        while (this.open) {
            synchronized (this.lock) {
                if (this.minLevel < LEVEL_COUNT) {
                    Level level = this.levels[this.minLevel];
                    List<ChunkTask<?>> tasks = level.take();
                    if (tasks != null) {
                        this.findMinLevel();
                        return tasks;
                    }
                }

                this.lock.wait();
            }
        }

        return null;
    }

    private void findMinLevel() {
        while (++this.minLevel < LEVEL_COUNT) {
            if (!this.levels[this.minLevel].isEmpty()) {
                break;
            }
        }
    }

    @Override
    public void close() {
        synchronized (this.lock) {
            this.open = false;
            this.lock.notify();
        }
    }

    Waker waker(ChunkTask<?> task) {
        return new Waker(task);
    }

    static class Level {
        List<ChunkTask<?>> queue = this.newQueue();

        void enqueue(ChunkTask<?> task) {
            this.queue.add(task);
        }

        List<ChunkTask<?>> take() {
            List<ChunkTask<?>> queue = this.queue;
            this.queue = this.newQueue();
            return queue;
        }

        boolean isEmpty() {
            return this.queue.isEmpty();
        }

        List<ChunkTask<?>> newQueue() {
            return new ArrayList<>(4);
        }
    }

    public class Waker implements net.gegy1000.justnow.Waker {
        static final int READY = 0;
        static final int POLLING = 1;
        static final int AWOKEN = 2;

        private final ChunkTask<?> task;

        final AtomicInteger state = new AtomicInteger(AWOKEN);

        Waker(ChunkTask<?> task) {
            this.task = task;
        }

        @Override
        public void wake() {
            // if we are currently polling, set state to awoken and don't re-enqueue the task until we are ready again
            if (this.state.compareAndSet(POLLING, AWOKEN)) {
                return;
            }

            // if we are currently ready, set state to awoken and re-enqueue the task
            if (this.state.compareAndSet(READY, AWOKEN)) {
                ChunkTaskQueue.this.enqueue(this.task);
            }
        }

        void polling() {
            this.state.set(POLLING);
        }

        void ready() {
            // we didn't get a result: set state to ready. we expect state to still be polling, so if that's not
            // the case, we must've been awoken during polling. now that we know this task needs to continue
            // execution, we can re-enqueue it.
            if (!this.state.compareAndSet(POLLING, READY)) {
                ChunkTaskQueue.this.enqueue(this.task);
            }
        }
    }
}
