package net.gegy1000.acttwo.chunk.worker;

import net.gegy1000.acttwo.chunk.tracker.ChunkLeveledTracker;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkTaskQueue implements AutoCloseable {
    private static final int LEVEL_COUNT = ChunkLeveledTracker.MAX_LEVEL + 2;

    private final Level[] levels;
    private volatile int minLevel;

    private volatile boolean open = true;

    private final Object lock = new Object();
    private Runnable notify;

    public ChunkTaskQueue() {
        this.levels = new Level[LEVEL_COUNT];
        for (int i = 0; i < LEVEL_COUNT; i++) {
            this.levels[i] = new Level();
        }

        this.minLevel = LEVEL_COUNT;
    }

    // TODO: don't like this
    public void onNotify(Runnable notify) {
        this.notify = notify;
    }

    public void enqueue(ChunkTask<?> task) {
        int level = task.holder.getLevel();
        if (level > ChunkLeveledTracker.MAX_LEVEL) {
            return;
        }

        synchronized (this.lock) {
            this.levels[level].enqueue(task);

            if (level <= this.minLevel) {
                this.minLevel = level;
                this.lock.notify();
            }
        }

        if (this.notify != null) {
            this.notify.run();
        }
    }

    // TODO: can we make this faster / reduce queue allocation?
    //         we can make use of LockSupport.park/unpark and atomics and avoid the lock

    @Nullable
    public ChunkTask<?> take() throws InterruptedException {
        while (this.open) {
            synchronized (this.lock) {
                if (this.minLevel < LEVEL_COUNT) {
                    ChunkTask<?> task = this.tryTakeTask(this.minLevel);
                    if (task != null) {
                        return task;
                    }
                }

                this.lock.wait();
            }
        }

        return null;
    }

    @Nullable
    public ChunkTask<?> remove() {
        synchronized (this.lock) {
            int minLevel = this.minLevel;
            if (minLevel < LEVEL_COUNT) {
                ChunkTask<?> task = this.tryTakeTask(minLevel);
                if (task != null) {
                    return task;
                }
            }
        }

        return null;
    }

    @Nullable
    private ChunkTask<?> tryTakeTask(int level) {
        ChunkTask<?> task = this.levels[level].take();
        if (task != null) {
            this.minLevel = this.findMinLevel(level);
            return task;
        }
        return null;
    }

    private int findMinLevel(int level) {
        while (level < LEVEL_COUNT && this.levels[level].isEmpty()) {
            level++;
        }
        return level;
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

    public boolean isEmpty() {
        for (Level level : this.levels) {
            if (!level.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static class Level {
        LinkedList<ChunkTask<?>> queue = new LinkedList<>();

        void enqueue(ChunkTask<?> task) {
            this.queue.add(task);
        }

        ChunkTask<?> take() {
            return this.queue.remove();
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
