package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RwLock {
    private static final int FREE = 0;
    private static final int WRITING = -1;

    private final Read read = new Read();
    private final Write write = new Write();

    private final AtomicInteger state = new AtomicInteger(FREE);

    // linked queue of waiting futures and their corresponding wakers
    private final AtomicReference<LockWaiter> waiter = new AtomicReference<>();

    public Lock read() {
        return this.read;
    }

    public Lock write() {
        return this.write;
    }

    boolean tryAcquireRead() {
        int state = this.state.get();
        return state != WRITING && this.state.compareAndSet(state, state + 1);
    }

    boolean canAcquireRead() {
        return this.state.get() != WRITING;
    }

    boolean tryAcquireWrite() {
        int state = this.state.get();
        return state == FREE && this.state.compareAndSet(FREE, WRITING);
    }

    boolean canAcquireWrite() {
        return this.state.get() == FREE;
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

    void registerWaiter(LockWaiter waiter, Waker waker) {
        // initialize the waker on the waiter object
        waiter.setWaker(waker);

        // this waiter object is already registered to the queue
        if (waiter.isLinked()) {
            return;
        }

        while (true) {
            // try swap the root node with our node. if it fails, try again
            LockWaiter root = this.waiter.get();

            // this waiter object is already registered to the queue
            if (root == waiter) {
                return;
            }

            waiter.linkTo(root);

            if (this.waiter.compareAndSet(root, waiter)) {
                return;
            }
        }
    }

    void wake() {
        LockWaiter waiter = this.waiter.getAndSet(null);
        if (waiter != null) {
            waiter.wake();
        }
    }

    private final class Read implements Lock {
        @Override
        public boolean tryAcquire() {
            return RwLock.this.tryAcquireRead();
        }

        @Override
        public boolean canAcquire() {
            return RwLock.this.canAcquireRead();
        }

        @Override
        public void release() {
            RwLock.this.releaseRead();
        }

        @Override
        public boolean tryAcquireAsync(LockWaiter waiter, Waker waker) {
            if (this.tryAcquire()) {
                return true;
            }

            RwLock.this.registerWaiter(waiter, waker);
            return false;
        }
    }

    private final class Write implements Lock {
        @Override
        public boolean tryAcquire() {
            return RwLock.this.tryAcquireWrite();
        }

        @Override
        public boolean canAcquire() {
            return RwLock.this.canAcquireWrite();
        }

        @Override
        public void release() {
            RwLock.this.releaseWrite();
        }

        @Override
        public boolean tryAcquireAsync(LockWaiter waiter, Waker waker) {
            if (this.tryAcquire()) {
                return true;
            }

            RwLock.this.registerWaiter(waiter, waker);
            return false;
        }
    }
}
