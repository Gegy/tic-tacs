package net.gegy1000.tictacs.async.worker;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;

public final class ChunkTask<T> {
    public final ChunkHolder holder;

    public final Future<T> future;
    public final ChunkWaker waker = new ChunkWaker(this);

    private volatile TaskQueue queue;

    private volatile boolean complete;

    ChunkTask(ChunkHolder holder, Future<T> future, TaskQueue queue) {
        this.holder = holder;
        this.future = future;
        this.queue = queue;
    }

    public void moveTo(TaskQueue queue) {
        this.queue = queue;
    }

    void advance() {
        if (this.complete) return;

        try {
            this.waker.polling();
            if (this.future.poll(this.waker) != null) {
                this.complete = true;
            } else {
                this.waker.ready();
            }
        } catch (RuntimeException e) {
            this.complete = true;
            throw new Error(e);
        }
    }

    void enqueue() {
        if (this.complete) return;

        this.queue.enqueue(this);
    }

    public boolean isComplete() {
        return this.complete;
    }
}
