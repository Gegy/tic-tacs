package net.gegy1000.acttwo.async.lock;

import net.gegy1000.acttwo.async.LinkedWaiter;
import net.gegy1000.justnow.Waker;

public final class FreeLock implements Lock {
    public static final Lock INSTANCE = new FreeLock();

    private FreeLock() {
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
    public boolean tryAcquireAsync(LinkedWaiter waiter, Waker waker) {
        return true;
    }
}
