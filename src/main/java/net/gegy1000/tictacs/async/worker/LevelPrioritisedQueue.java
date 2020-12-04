package net.gegy1000.tictacs.async.worker;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedList;

public final class LevelPrioritisedQueue<T> implements AutoCloseable {
    private final int levelCount;
    private final Level<T>[] levels;
    private volatile int minLevel;

    private volatile boolean open = true;

    private final Object lock = new Object();

    @SuppressWarnings("unchecked")
    public LevelPrioritisedQueue(int levelCount) {
        this.levelCount = levelCount;

        this.levels = new Level[levelCount];
        for (int i = 0; i < levelCount; i++) {
            this.levels[i] = new Level<>();
        }

        this.minLevel = levelCount;
    }

    public void enqueue(T task, int level) {
        if (level >= this.levelCount) {
            level = this.levelCount - 1;
        }

        synchronized (this.lock) {
            this.levels[level].enqueue(task);

            if (level <= this.minLevel) {
                this.minLevel = level;
                this.lock.notify();
            }
        }
    }

    @Nullable
    public T take() throws InterruptedException {
        while (this.open) {
            synchronized (this.lock) {
                if (this.minLevel < this.levelCount) {
                    T task = this.tryTakeTask(this.minLevel);
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
    public T remove() {
        synchronized (this.lock) {
            int minLevel = this.minLevel;
            if (minLevel < this.levelCount) {
                T task = this.tryTakeTask(minLevel);
                if (task != null) {
                    return task;
                }
            }
        }

        return null;
    }

    @Nullable
    private T tryTakeTask(int level) {
        T task = this.levels[level].take();
        if (task != null) {
            this.minLevel = this.findMinLevel(level);
            return task;
        }
        return null;
    }

    private int findMinLevel(int level) {
        while (level < this.levelCount && this.levels[level].isEmpty()) {
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

    static class Level<T> {
        private final LinkedList<T> queue = new LinkedList<>();

        void enqueue(T task) {
            this.queue.add(task);
        }

        T take() {
            return this.queue.remove();
        }

        boolean isEmpty() {
            return this.queue.isEmpty();
        }
    }
}
