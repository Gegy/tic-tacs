package net.gegy1000.acttwo.chunk.worker;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ChunkWorker implements AutoCloseable {
    public static final ChunkWorker INSTANCE = new ChunkWorker();
    private static final Logger LOGGER = LogManager.getLogger("worldgen-worker");

    private final ChunkExecutor executor = new ChunkExecutor();

    private ChunkWorker() {
        // TODO: change thread count based on config / core count
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(this::run);
            thread.setName("worldgen-worker-" + (i + 1));
            thread.setDaemon(true);
            thread.start();
        }
    }

    public <T> void spawn(ChunkHolder holder, Future<T> future) {
        this.executor.spawn(holder, future);
    }

    private void run() {
        try {
            this.executor.run();
        } catch (InterruptedException e) {
            LOGGER.warn("chunkgen worker interrupted", e);
        }
    }

    @Override
    public void close() {
        this.executor.close();
    }
}
