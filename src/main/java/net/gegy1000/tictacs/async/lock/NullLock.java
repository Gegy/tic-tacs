package net.gegy1000.tictacs.async.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.tictacs.async.LinkedWaiter;

public final class NullLock implements Lock {
    public static final Lock INSTANCE = new NullLock();

    private NullLock() {
    }

    @Override
    public boolean tryAcquire() {
        return true;
    }

    @Override
    public boolean canAcquire() {
        return true;
    }

    @Override
    public void release() {
    }

    @Override
    public PollLock tryPollLock(LinkedWaiter waiter, Waker waker) {
        return PollLock.ACQUIRED;
    }
}
