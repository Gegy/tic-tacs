package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;

public class LockWaiter {
    volatile Waker waker;
    volatile LockWaiter previous;

    public void setWaker(Waker waker) {
        this.waker = waker;
    }

    public void linkTo(LockWaiter previous) {
        this.previous = previous;
    }

    public boolean isLinked() {
        return this.previous != null;
    }

    public void wake() {
        LockWaiter waiter = this;

        while (waiter != null) {
            LockWaiter next = waiter.previous;

            Waker waker = waiter.waker;
            if (waker != null) {
                waker.wake();
            }

            waiter.waker = null;
            waiter.previous = null;

            waiter = next;
        }
    }

    public void invalidateWaker() {
        this.waker = null;
    }
}
