package net.gegy1000.tictacs.async;

import net.gegy1000.justnow.Waker;
import net.gegy1000.tictacs.util.UnsafeAccess;
import sun.misc.Unsafe;

public final class WaiterQueue {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();

    private static final long HEAD_OFFSET;
    private static final long TAIL_OFFSET;

    static {
        try {
            HEAD_OFFSET = UNSAFE.objectFieldOffset(WaiterQueue.class.getDeclaredField("head"));
            TAIL_OFFSET = UNSAFE.objectFieldOffset(WaiterQueue.class.getDeclaredField("tail"));
        } catch (NoSuchFieldException e) {
            throw new Error("Failed to get waiter field offsets", e);
        }
    }

    private final LinkedWaiter head = new LinkedWaiter();
    private volatile LinkedWaiter tail = this.head;

    public WaiterQueue() {
        this.resetTail();
    }

    private void resetTail() {
        LinkedWaiter head = this.head;

        this.tail = head;
        head.openLink();
    }

    public void registerWaiter(LinkedWaiter waiter, Waker waker) {
        // initialize the waker on the waiter object
        waiter.setWaker(waker);

        // this waiter object is already registered to the queue
        // we know this waiter hasn't been woken up yet if it is linked: the link is the first thing reset
        if (waiter.isLinked()) {
            return;
        }

        while (true) {
            LinkedWaiter tail = this.tail;

            if (tail.tryLink(waiter)) {
                // swap the tail reference. we can safely ignore failure
                // it's safe for the tail to lag behind, because this is checked when trying to link the waiter
                UNSAFE.compareAndSwapObject(this, TAIL_OFFSET, tail, waiter);

                // only open the link once we've swapped the tail
                waiter.openLink();

                return;
            }
        }
    }

    public void wake() {
        // unlink the waiter chain from the head
        LinkedWaiter waiter = this.head.unlinkNext();

        this.resetTail();

        if (waiter != null) {
            waiter.wake();
        }
    }
}
