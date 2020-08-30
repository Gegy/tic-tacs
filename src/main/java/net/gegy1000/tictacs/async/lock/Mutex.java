package net.gegy1000.tictacs.async.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.tictacs.async.LinkedWaiter;
import net.gegy1000.tictacs.async.WaiterQueue;
import net.gegy1000.tictacs.util.UnsafeAccess;
import sun.misc.Unsafe;

public final class Mutex implements Lock {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();
    private static final long STATE_OFFSET;

    private static final int FREE = 0;
    private static final int ACQUIRED = 1;

    static {
        try {
            STATE_OFFSET = UNSAFE.objectFieldOffset(Mutex.class.getDeclaredField("state"));
        } catch (NoSuchFieldException e) {
            throw new Error("Failed to get state field offsets", e);
        }
    }

    private volatile int state = FREE;
    private final WaiterQueue waiters = new WaiterQueue();

    @Override
    public boolean tryAcquire() {
        return UNSAFE.compareAndSwapInt(this, STATE_OFFSET, FREE, ACQUIRED);
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
        return this.state == FREE;
    }

    @Override
    public void release() {
        if (!UNSAFE.compareAndSwapInt(this, STATE_OFFSET, ACQUIRED, FREE)) {
            throw new IllegalStateException("lock not acquired");
        }
        this.waiters.wake();
    }

    @Override
    public String toString() {
        if (this.canAcquire()) {
            return "Mutex(FREE)";
        } else {
            return "Mutex(ACQUIRED)";
        }
    }
}
