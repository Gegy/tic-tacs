package net.gegy1000.acttwo.chunk;

import net.gegy1000.justnow.executor.LocalExecutor;
import net.gegy1000.justnow.executor.TaskHandle;
import net.gegy1000.justnow.future.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ChunkGenWorker implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("worldgen-worker");

    // TODO: potentially inline the executor
    // TODO: also! we need to be able to interrupt the waiting for task on close!
    //   interrupting the thread is a temporary solution
    //    e.g: localexecutor needs to be closeable
    private final LocalExecutor executor = new LocalExecutor();
    private final Thread thread;

    private volatile boolean active = true;

    public ChunkGenWorker() {
        this.thread = new Thread(this::run);
        this.thread.setName("worldgen-worker");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public <T> TaskHandle<T> spawn(Future<T> future) {
        return this.executor.spawn(future);
    }

    private void run() {
        try {
            this.executor.runWhile(() -> this.active);
        } catch (InterruptedException e) {
        }
    }

    @Override
    public void close() {
        this.active = false;
        this.thread.interrupt();
    }
}
