package net.gegy1000.tictacs.async.worker;

import net.gegy1000.tictacs.chunk.ChunkLevelTracker;

import javax.annotation.Nullable;
import java.util.LinkedList;

public final class ChunkPrioritisedQueue implements TaskQueue, AutoCloseable {
    private static final int LEVEL_COUNT = ChunkLevelTracker.MAX_LEVEL + 2;

    private final Level[] levels;
    private volatile int minLevel;

    private volatile boolean open = true;

    private final Object lock = new Object();

    public ChunkPrioritisedQueue() {
        this.levels = new Level[LEVEL_COUNT];
        for (int i = 0; i < LEVEL_COUNT; i++) {
            this.levels[i] = new Level();
        }

        this.minLevel = LEVEL_COUNT;
    }

    @Override
    public <T> void enqueue(ChunkTask<T> task) {
        int level = task.holder.getLevel();
        if (level > ChunkLevelTracker.MAX_LEVEL) {
            return;
        }

        synchronized (this.lock) {
            this.levels[level].enqueue(task);

            if (level <= this.minLevel) {
                this.minLevel = level;
                this.lock.notifyAll();
            }
        }
    }

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
            this.lock.notifyAll();
        }
    }

    static class Level {
        private final LinkedList<ChunkTask<?>> queue = new LinkedList<>();

        void enqueue(ChunkTask<?> task) {
            this.queue.add(task);
        }

        ChunkTask<?> take() {
            return this.queue.remove();
        }

        boolean isEmpty() {
            return this.queue.isEmpty();
        }
    }
}
