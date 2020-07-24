package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class Mutex implements Lock {
    private static final int FREE = 0;
    private static final int ACQUIRED = 1;

    private final AtomicInteger state = new AtomicInteger(FREE);

    // linked queue of waiting futures and their corresponding wakers
    private final AtomicReference<LockWaiter> waiter = new AtomicReference<>();

    @Override
    public boolean tryAcquire() {
        int state = this.state.get();
        return state == FREE && this.state.compareAndSet(FREE, ACQUIRED);
    }

    @Override
    public boolean tryAcquireAsync(LockWaiter waiter, Waker waker) {
        if (this.tryAcquire()) {
            return true;
        }

        this.registerWaiter(waiter, waker);
        return false;
    }

    @Override
    public boolean canAcquire() {
        return this.state.get() == FREE;
    }

    @Override
    public void release() {
        if (!this.state.compareAndSet(ACQUIRED, FREE)) {
            throw new IllegalStateException("lock not acquired");
        }
        this.wake();
    }

    // TODO: extract common logic in waiter registration?
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
}
