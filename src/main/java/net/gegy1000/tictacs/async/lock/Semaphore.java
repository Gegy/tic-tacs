package net.gegy1000.tictacs.async.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.tictacs.async.LinkedWaiter;
import net.gegy1000.tictacs.async.WaiterQueue;
import net.gegy1000.tictacs.util.UnsafeAccess;
import sun.misc.Unsafe;

public final class Semaphore implements Lock {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();
    private static final long COUNT_OFFSET;

    static {
        try {
            COUNT_OFFSET = UNSAFE.objectFieldOffset(Semaphore.class.getDeclaredField("count"));
        } catch (NoSuchFieldException e) {
            throw new Error("Failed to get count field offsets", e);
        }
    }

    private final int maximum;
    private volatile int count = 0;

    private final WaiterQueue waiters = new WaiterQueue();

    public Semaphore(int maximum) {
        this.maximum = maximum;
    }

    private boolean canAcquire(int count) {
        return count < this.maximum;
    }

    @Override
    public boolean tryAcquire() {
        while (true) {
            int count = this.count;
            if (!this.canAcquire(count)) {
                return false;
            }

            if (UNSAFE.compareAndSwapInt(this, COUNT_OFFSET, count, count + 1)) {
                return true;
            }
        }
    }

    @Override
    public PollLock tryPollLock(LinkedWaiter waiter, Waker waker) {
        if (!this.tryAcquire()) {
            this.waiters.registerWaiter(waiter, waker);
            return this.canAcquire() ? PollLock.RETRY : PollLock.PENDING;
        }

        return PollLock.ACQUIRED;
    }

    @Override
    public boolean canAcquire() {
        return this.canAcquire(this.count);
    }

    @Override
    public void release() {
        int count = UNSAFE.getAndAddInt(this, COUNT_OFFSET, -1);
        if (count <= 0) {
            throw new IllegalStateException("semaphore not acquired");
        }

        int newCount = count - 1;

        int available = this.maximum - newCount;
        if (available > 0) {
            this.waiters.wake(available);
        }
    }
}
