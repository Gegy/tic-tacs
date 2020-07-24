package net.gegy1000.acttwo.async.lock;

import net.gegy1000.acttwo.async.LinkedWaiter;
import net.gegy1000.acttwo.async.WaiterList;
import net.gegy1000.justnow.Waker;

import java.util.concurrent.atomic.AtomicInteger;

public final class Semaphore implements Lock {
    private final int maximum;
    private final AtomicInteger count = new AtomicInteger(0);

    private final WaiterList waiters = new WaiterList();

    public Semaphore(int maximum) {
        this.maximum = maximum;
    }

    private boolean canAcquire(int count) {
        return count < this.maximum;
    }

    @Override
    public boolean tryAcquire() {
        int count = this.count.get();
        if (!this.canAcquire(count)) {
            return false;
        }

        return this.count.compareAndSet(count, count + 1);
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
        return this.canAcquire(this.count.get());
    }

    @Override
    public void release() {
        int count = this.count.decrementAndGet();
        if (count < 0) {
            throw new IllegalStateException("semaphore not acquired");
        }

        if (this.canAcquire(count)) {
            this.waiters.wake();
        }
    }
}
