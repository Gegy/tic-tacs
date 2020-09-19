package net.gegy1000.tictacs.async.worker;

import net.gegy1000.justnow.future.Future;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.config.TicTacsConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ChunkExecutor implements TaskSpawner, TaskQueue, AutoCloseable {
    public static final ChunkExecutor INSTANCE = new ChunkExecutor();

    private static final Logger LOGGER = LogManager.getLogger("worldgen-worker");

    private final LevelPrioritisedQueue<ChunkTask<?>> queue = new LevelPrioritisedQueue<>(ChunkLevelTracker.MAX_LEVEL);

    private ChunkExecutor() {
        for (int i = 0; i < TicTacsConfig.get().threadCount; i++) {
            Thread thread = new Thread(this::run);
            thread.setName("worldgen-worker-" + (i + 1));
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public <T> ChunkTask<T> spawn(ChunkEntry entry, Future<T> future) {
        ChunkTask<T> task = new ChunkTask<>(entry, future, this);
        this.queue.enqueue(task, entry.getLevel());
        return task;
    }

    @Override
    public <T> void enqueue(ChunkTask<T> task) {
        this.queue.enqueue(task, task.getLevel());
    }

    public void run() {
        try {
            ChunkTask<?> task;
            while ((task = this.queue.take()) != null) {
                task.advance();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("worldgen worker interrupted", e);
        }
    }

    @Override
    public void close() {
        this.queue.close();
    }
}
