package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RwLock<T> {
    static final int FREE = 0;
    static final int WRITING = -1;

    T inner;

    final AtomicInteger state = new AtomicInteger();

    // linked-queue of waiting futures and their corresponding wakers
    final AtomicReference<Waiting> waiting = new AtomicReference<>();

    final Read read = new Read();
    final Write write = new Write();

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

    public boolean tryAcquireRead() {
        int state = this.state.get();
        return state != WRITING && RwLock.this.state.compareAndSet(state, state + 1);
    }

    public boolean canAcquireRead() {
        return this.state.get() != WRITING;
    }

    public boolean tryAcquireWrite() {
        int state = this.state.get();
        return state == FREE && RwLock.this.state.compareAndSet(FREE, WRITING);
    }

    public boolean canAcquireWrite() {
        return this.state.get() == FREE;
    }

    public void releaseWrite() {
        if (!this.state.compareAndSet(WRITING, FREE)) {
            throw new IllegalStateException("write lock not acquired");
        }

        this.wake();
    }

    public void releaseRead() {
        int state = this.state.getAndDecrement();
        if (state <= 0) {
            throw new IllegalStateException("read lock not acquired");
        }

        int readCount = state - 1;
        if (readCount <= 0) {
            this.wake();
        }
    }

    public void registerWaiting(Waiting waiting, Waker waker) {
        // initialize the waker on the waiting object
        waiting.waker = waker;

        // this waiting object is already registered to the queue
        if (waiting.previous != null) {
            return;
        }

        while (true) {
            // try swap the root node with our node. if it fails, try again
            Waiting root = this.waiting.get();

            // this waiting object is already registered to the queue
            if (root == waiting) {
                return;
            }

            waiting.previous = root;

            if (this.waiting.compareAndSet(root, waiting)) {
                return;
            }
        }
    }

    private void wake() {
        Waiting waiting = this.waiting.getAndSet(null);
        if (waiting != null) {
            waiting.wake();
        }
    }

    public static class Waiting {
        // we don't need atomic here because they can only be modified by their owner
        volatile Waker waker;
        volatile Waiting previous;

        void wake() {
            Waiting previous = this.previous;
            if (previous != null) {
                this.previous = null;
                previous.wake();
            }

            Waker waker = this.waker;
            if (waker != null) {
                this.waker = null;
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
                if (RwLock.this.tryAcquireRead()) {
                    return RwLock.this.read;
                }

                // we failed to acquire the read lock, register our waker
                RwLock.this.registerWaiting(this, waker);

                // if the lock is still acquired, there's nothing more to be done for now
                if (!RwLock.this.canAcquireRead()) {
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
                if (RwLock.this.tryAcquireWrite()) {
                    return RwLock.this.write;
                }

                // we failed to acquire the read lock, register our waker
                RwLock.this.registerWaiting(this, waker);

                // if the lock is still acquired, there's nothing more to be done for now
                if (!RwLock.this.canAcquireWrite()) {
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
