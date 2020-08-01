package net.gegy1000.tictacs;

import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.util.thread.TaskQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class OwnThreadActor<T> extends TaskExecutor<T> {
    private static final Logger LOGGER = LogManager.getLogger(OwnThreadActor.class);
    private static final Map<String, OwnThreadActor<Runnable>> ACTORS = new HashMap<>();

    private final Object lock = new Object();

    private final AtomicInteger refCount = new AtomicInteger();
    private volatile boolean active = true;

    private OwnThreadActor(TaskQueue<? super T, ? extends Runnable> queue, String name) {
        super(queue, task -> {}, name);
    }

    public static OwnThreadActor<Runnable> create(String name) {
        OwnThreadActor<Runnable> actor;
        synchronized (ACTORS) {
            // merge actors by the same name
            actor = ACTORS.computeIfAbsent(name, OwnThreadActor::startActor);
        }

        actor.acquireRef();

        return actor;
    }

    private static OwnThreadActor<Runnable> startActor(String name) {
        TaskQueue.Simple<Runnable> queue = new TaskQueue.Simple<>(new ArrayDeque<>());
        OwnThreadActor<Runnable> actor = new OwnThreadActor<>(queue, name);

        Thread thread = new Thread(actor);
        thread.setName(name + "-actor");
        thread.setDaemon(true);
        thread.start();

        return actor;
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

    private void acquireRef() {
        this.refCount.getAndIncrement();
    }

    private boolean releaseRef() {
        return this.refCount.decrementAndGet() <= 0;
    }

    private void stop() {
        synchronized (this.lock) {
            this.active = false;
            this.lock.notify();
        }

        synchronized (ACTORS) {
            ACTORS.remove(this.getName());
        }
    }

    @Override
    public void close() {
        super.close();

        if (this.releaseRef()) {
            this.stop();
        }
    }
}
