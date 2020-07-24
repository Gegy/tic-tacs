package net.gegy1000.acttwo.async;

import net.gegy1000.justnow.Waker;

public class LinkedWaiter {
    volatile Waker waker;
    volatile LinkedWaiter previous;

    public void setWaker(Waker waker) {
        this.waker = waker;
    }

    public void linkTo(LinkedWaiter previous) {
        this.previous = previous;
    }

    public boolean isLinked() {
        return this.previous != null;
    }

    public void wake() {
        LinkedWaiter waiter = this;

        while (waiter != null) {
            LinkedWaiter next = waiter.previous;

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
