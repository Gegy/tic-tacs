package net.gegy1000.acttwo.chunk.worker;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public final class ChunkGenWorker implements AutoCloseable {
    public static final ChunkGenWorker INSTANCE = new ChunkGenWorker();
    private static final Logger LOGGER = LogManager.getLogger("worldgen-worker");

    private final ChunkTaskQueue queue;

    private ChunkGenWorker() {
        this.queue = new ChunkTaskQueue();

        Thread thread = new Thread(this::run);
        thread.setName("worldgen-worker");
        thread.setDaemon(true);
        thread.start();
    }

    public <T> void spawn(ChunkHolder holder, Future<T> future) {
        ChunkTask<T> task = new ChunkTask<>(holder, future, this.queue);
        this.queue.enqueue(task);
    }

    private void run() {
        try {
            List<ChunkTask<?>> queue;
            while ((queue = this.queue.take()) != null) {
                for (ChunkTask<?> task : queue) {
                    task.advance();
                }
            }
        } catch (InterruptedException e) {
            LOGGER.warn("chunkgen worker interrupted", e);
        }
    }

    @Override
    public void close() {
        this.queue.close();
    }
}
