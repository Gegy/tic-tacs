package net.gegy1000.acttwo;

import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.util.thread.TaskQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;

public final class OwnThreadActor<T> extends TaskExecutor<T> {
    private static final Logger LOGGER = LogManager.getLogger(OwnThreadActor.class);

    private final Object lock = new Object();

    private volatile boolean active = true;

    private OwnThreadActor(TaskQueue<? super T, ? extends Runnable> queue, String name) {
        super(queue, task -> {}, name);

        Thread thread = new Thread(this);
        thread.setName("actor-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    public static OwnThreadActor<Runnable> create(String name) {
        TaskQueue.Simple<Runnable> queue = new TaskQueue.Simple<>(new ArrayDeque<>());
        return new OwnThreadActor<>(queue, name);
    }

    @Override
    public void run() {
        try {
            while (this.active) {
                Runnable task;
                synchronized (this.lock) {
                    while ((task = this.queue.poll()) == null) {
                        if (!this.active) return;
                        this.lock.wait();
                    }
                }

                task.run();
            }
        } catch (InterruptedException e) {
            LOGGER.error("Actor thread interrupted", e);
        }
    }

    @Override
    public void send(T message) {
        synchronized (this.lock) {
            this.queue.add(message);
            this.lock.notify();
        }
    }

    @Override
    public void close() {
        super.close();

        synchronized (this.lock) {
            this.active = false;
            this.lock.notify();
        }
    }
}
