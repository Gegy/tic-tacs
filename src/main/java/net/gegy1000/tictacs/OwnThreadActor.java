package net.gegy1000.tictacs;

import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.util.thread.TaskQueue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class OwnThreadActor<T> extends TaskExecutor<T> {
    private static final Map<String, OwnThreadActor<Runnable>> ACTORS = new HashMap<>();

    private final Thread thread;
    private final AtomicInteger refCount = new AtomicInteger();

    private final AtomicBoolean parked = new AtomicBoolean();
    private volatile boolean active = true;

    private OwnThreadActor(TaskQueue<? super T, ? extends Runnable> queue, String name) {
        super(queue, task -> {}, name);

        this.thread = new Thread(this);
        this.thread.setName(name + "-actor");
        this.thread.setDaemon(true);
        this.thread.start();
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
        return new OwnThreadActor<>(new TaskQueue.Simple<>(new ConcurrentLinkedQueue<>()), name);
    }

    @Override
    public void run() {
        while (this.active) {
            Runnable task;
            while ((task = this.queue.poll()) == null) {
                if (!this.active) return;
                this.park();
            }

            task.run();
        }
    }

    @Override
    public void send(T message) {
        this.queue.add(message);
        this.unpark();
    }

    private void acquireRef() {
        this.refCount.getAndIncrement();
    }

    private boolean releaseRef() {
        return this.refCount.decrementAndGet() <= 0;
    }

    private void stop() {
        this.active = false;
        this.unpark();

        synchronized (ACTORS) {
            ACTORS.remove(this.getName());
        }
    }

    private void park() {
        this.parked.set(true);
        LockSupport.park();
    }

    private void unpark() {
        if (this.parked.compareAndSet(true, false)) {
            LockSupport.unpark(this.thread);
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
