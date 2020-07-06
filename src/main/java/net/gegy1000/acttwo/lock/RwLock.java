package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RwLock<T> {
    private static final int FREE = 0;
    private static final int WRITING = -1;

    private T inner;

    private final AtomicInteger state = new AtomicInteger();

    // linked-queue of waiting futures and their corresponding wakers
    private final AtomicReference<Waiting> waiting = new AtomicReference<>();

    private final Read read = new Read();
    private final Write write = new Write();

    public RwLock(T inner) {
        this.inner = inner;
    }

    // TODO: if the lock is free, return a ready future directly
    public Future<RwGuard<T>> read() {
        return new PollRead();
    }

    public Future<WriteRwGuard<T>> write() {
        return new PollWrite();
    }

    public T getInnerUnsafe() {
        return this.inner;
    }

    private void registerWaiting(Waiting waiting, Waker waker) {
        // initialize the waker on the waiting object
        waiting.waker = waker;

        // this waiting object is already registered to the queue
        if (waiting.previous != null) {
            return;
        }

        while (true) {
            // try swap the root node with our node. if it fails, try again
            Waiting root = this.waiting.get();
            waiting.previous = root;

            if (this.waiting.compareAndSet(root, waiting)) {
                return;
            }
        }
    }

    void releaseWrite() {
        if (!this.state.compareAndSet(WRITING, FREE)) {
            throw new IllegalStateException("write lock not acquired");
        }

        this.wake();
    }

    void releaseRead() {
        int state = this.state.getAndDecrement();
        if (state <= 0) {
            throw new IllegalStateException("read lock not acquired");
        }

        int readCount = state - 1;
        if (readCount <= 0) {
            this.wake();
        }
    }

    private void wake() {
        Waiting waiting = this.waiting.getAndSet(null);
        if (waiting != null) {
            waiting.wake();
        }
    }

    static class Waiting {
        // we don't need atomic here because they can only be modified by their owner
        volatile Waker waker;
        volatile Waiting previous;

        void wake() {
            Waiting next = this.previous;
            if (next != null) {
                next.wake();
            }

            Waker waker = this.waker;
            if (waker != null) {
                waker.wake();
            }
        }

        void invalidateWaker() {
            this.waker = null;
        }
    }

    final class PollRead extends Waiting implements ReadFuture<T> {
        @Nullable
        @Override
        public RwGuard<T> poll(Waker waker) {
            while (true) {
                // invalidate our waker if it is queued
                this.invalidateWaker();

                // if the write lock is not acquired, attempt to increment the read counter
                int state = RwLock.this.state.get();
                if (state != WRITING && RwLock.this.state.compareAndSet(state, state + 1)) {
                    return RwLock.this.read;
                }

                // we failed to acquire the read lock, register our waker
                RwLock.this.registerWaiting(this, waker);

                // if the lock is still acquired, there's nothing more to be done for now
                if (RwLock.this.state.get() == WRITING) {
                    return null;
                }
            }
        }
    }

    final class PollWrite extends Waiting implements WriteFuture<T> {
        @Nullable
        @Override
        public WriteRwGuard<T> poll(Waker waker) {
            while (true) {
                // invalidate our waker if it is queued
                this.invalidateWaker();

                // if the lock is free, attempt to acquire as a writer
                int state = RwLock.this.state.get();
                if (state == FREE && RwLock.this.state.compareAndSet(FREE, WRITING)) {
                    return RwLock.this.write;
                }

                // we failed to acquire the read lock, register our waker
                RwLock.this.registerWaiting(this, waker);

                // if the lock is still acquired, there's nothing more to be done for now
                if (RwLock.this.state.get() != FREE) {
                    return null;
                }
            }
        }
    }

    final class Read implements RwGuard<T> {
        @Override
        public T get() {
            return RwLock.this.inner;
        }

        @Override
        public void release() {
            RwLock.this.releaseRead();
        }
    }

    final class Write implements WriteRwGuard<T> {
        @Override
        public void set(T value) {
            RwLock.this.inner = value;
        }

        @Override
        public T get() {
            return RwLock.this.inner;
        }

        @Override
        public void release() {
            RwLock.this.releaseWrite();
        }
    }
}
