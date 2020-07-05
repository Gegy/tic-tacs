package net.gegy1000.acttwo.chunk.worker;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkTask<T> {
    public final ChunkHolder holder;

    public final Future<T> future;
    public final ChunkTaskQueue.Waker waker;

    private final AtomicBoolean invalidated = new AtomicBoolean(false);

    public ChunkTask(ChunkHolder holder, Future<T> future, ChunkTaskQueue taskQueue) {
        this.holder = holder;
        this.future = future;
        this.waker = taskQueue.waker(this);
    }

    public void invalidate() {
        this.invalidated.set(true);
    }

    public boolean isInvalid() {
        return this.invalidated.get();
    }

    public void advance() {
        if (this.isInvalid()) return;

        try {
            this.waker.polling();
            if (this.future.poll(this.waker) != null) {
                this.invalidate();
            } else {
                this.waker.ready();
            }
        } catch (Throwable exception) {
            // TODO: error handling very bad
            exception.printStackTrace();
            this.invalidate();
        }
    }
}
