package net.gegy1000.acttwo.chunk;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;

public abstract class SharedListener<T> implements Future<T> {
    private final AtomicReference<Waiting> waiting = new AtomicReference<>();

    @Nullable
    protected abstract T get();

    protected final void wake() {
        Waiting waiting = this.waiting.getAndSet(null);
        if (waiting != null) {
            waiting.wake();
        }
    }

    @Nullable
    @Override
    public final T poll(Waker waker) {
        T value = this.get();
        if (value != null) {
            return value;
        }

        Waiting waiting = this.registerWaker(waker);

        // try get the value again in case one was set before we registered our waker
        value = this.get();
        if (value != null) {
            // if a value was set while we were registering, we can invalidate that waker now
            waiting.invalidate();
            return value;
        }

        return null;
    }

    private Waiting registerWaker(Waker waker) {
        Waiting waiting = new Waiting(waker);

        while (true) {
            // try swap the root node with our node. if it fails, try again
            Waiting root = this.waiting.get();
            waiting.previous = root;

            if (this.waiting.compareAndSet(root, waiting)) {
                return waiting;
            }
        }
    }

    static class Waiting {
        volatile Waker waker;
        volatile Waiting previous;

        Waiting(Waker waker) {
            this.waker = waker;
        }

        void invalidate() {
            this.waker = null;
        }

        void wake() {
            Waiting previous = this.previous;
            if (previous != null) {
                previous.wake();
            }

            Waker waker = this.waker;
            if (waker != null) {
                waker.wake();
            }
        }
    }
}
