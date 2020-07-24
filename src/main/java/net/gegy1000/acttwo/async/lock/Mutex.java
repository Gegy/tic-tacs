package net.gegy1000.acttwo.async.lock;

import net.gegy1000.acttwo.async.LinkedWaiter;
import net.gegy1000.acttwo.async.WaiterList;
import net.gegy1000.justnow.Waker;

import java.util.concurrent.atomic.AtomicInteger;

public final class Mutex implements Lock {
    private static final int FREE = 0;
    private static final int ACQUIRED = 1;

    private final AtomicInteger state = new AtomicInteger(FREE);
    private final WaiterList waiters = new WaiterList();

    @Override
    public boolean tryAcquire() {
        int state = this.state.get();
        return state == FREE && this.state.compareAndSet(FREE, ACQUIRED);
    }

    @Override
    public boolean tryAcquireAsync(LinkedWaiter waiter, Waker waker) {
        if (!this.tryAcquire()) {
            this.waiters.registerWaiter(waiter, waker);
            return false;
        }

        return true;
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
        this.waiters.wake();
    }
}
