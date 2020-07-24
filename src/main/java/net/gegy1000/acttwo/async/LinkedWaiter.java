package net.gegy1000.acttwo.async;

import net.gegy1000.acttwo.util.UnsafeAccess;
import net.gegy1000.justnow.Waker;
import sun.misc.Unsafe;

public class LinkedWaiter {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();

    private static final long WAKER_OFFSET;
    private static final long PREVIOUS_OFFSET;

    static {
        try {
            WAKER_OFFSET = UNSAFE.objectFieldOffset(LinkedWaiter.class.getDeclaredField("waker"));
            PREVIOUS_OFFSET = UNSAFE.objectFieldOffset(LinkedWaiter.class.getDeclaredField("previous"));
        } catch (NoSuchFieldException e) {
            throw new Error("Failed to get waiter field offsets", e);
        }
    }

    private volatile Waker waker;
    private volatile LinkedWaiter previous;

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
            LinkedWaiter next = (LinkedWaiter) UNSAFE.getAndSetObject(waiter, PREVIOUS_OFFSET, null);
            Waker waker = (Waker) UNSAFE.getAndSetObject(waiter, WAKER_OFFSET, null);

            if (waker != null) {
                waker.wake();
            }

            waiter = next;
        }
    }

    public void invalidateWaker() {
        this.waker = null;
    }
}
