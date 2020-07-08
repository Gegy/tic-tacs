package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SimpleRwLock<T> implements RwLock<T> {
    T inner;

    final AtomicInteger state = new AtomicInteger();

    // linked-queue of waiting futures and their corresponding wakers
    final AtomicReference<Waiting> waiting = new AtomicReference<>();

    final Read<T> read = new Read<>(this);
    final Write<T> write = new Write<>(this);

    SimpleRwLock(T inner) {
        this.inner = inner;
    }

    // TODO: if the lock is free, return a ready future directly
    @Override
    public Future<RwGuard<T>> read() {
        return new PollRead();
    }

    @Override
    public Future<WriteRwGuard<T>> write() {
        return new PollWrite();
    }

    @Override
    public void setInnerUnsafe(T inner) {
        this.inner = inner;
    }

    @Override
    public T getInnerUnsafe() {
        return this.inner;
    }

    @Override
    public boolean tryAcquireRead() {
        int state = this.state.get();
        return state != WRITING && SimpleRwLock.this.state.compareAndSet(state, state + 1);
    }

    @Override
    public boolean canAcquireRead() {
        return this.state.get() != WRITING;
    }

    @Override
    public boolean tryAcquireWrite() {
        int state = this.state.get();
        return state == FREE && SimpleRwLock.this.state.compareAndSet(FREE, WRITING);
    }

    @Override
    public boolean canAcquireWrite() {
        return this.state.get() == FREE;
    }

    @Override
    public void releaseWrite() {
        if (!this.state.compareAndSet(WRITING, FREE)) {
            throw new IllegalStateException("write lock not acquired");
        }

        this.wake();
    }

    @Override
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

    @Override
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

    final class PollRead extends Waiting implements ReadFuture<T> {
        @Nullable
        @Override
        public RwGuard<T> poll(Waker waker) {
            while (true) {
                // invalidate our waker if it is queued
                this.invalidateWaker();

                // if the write lock is not acquired, attempt to increment the read counter
                if (SimpleRwLock.this.tryAcquireRead()) {
                    return SimpleRwLock.this.read;
                }

                // we failed to acquire the read lock, register our waker
                SimpleRwLock.this.registerWaiting(this, waker);

                // if the lock is still acquired, there's nothing more to be done for now
                if (!SimpleRwLock.this.canAcquireRead()) {
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
                if (SimpleRwLock.this.tryAcquireWrite()) {
                    return SimpleRwLock.this.write;
                }

                // we failed to acquire the read lock, register our waker
                SimpleRwLock.this.registerWaiting(this, waker);

                // if the lock is still acquired, there's nothing more to be done for now
                if (!SimpleRwLock.this.canAcquireWrite()) {
                    return null;
                }
            }
        }
    }
}
