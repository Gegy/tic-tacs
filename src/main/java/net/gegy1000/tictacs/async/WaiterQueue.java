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
        this.head.openLink();
    }

    public void registerWaiter(LinkedWaiter waiter, Waker waker) {
        // initialize the waker on the waiter object
        waiter.setWaker(waker);

        // try to open the link on this waiter object
        // if this fails, we must be already linked into the queue
        if (!waiter.openLink()) {
            return;
        }

        while (true) {
            LinkedWaiter tail = this.tail;

            // by linking the tail, the tail is essentially locked until the tail reference is swapped
            if (tail.tryLink(waiter)) {
                // swap the tail reference: if we fail, we must've been woken up already. we can accept
                // ignoring the error because we know this node is enqueued to be awoken
                UNSAFE.compareAndSwapObject(this, TAIL_OFFSET, tail, waiter);

                return;
            }
        }
    }

    public void wake() {
        // unlink the waiter chain from the head
        LinkedWaiter waiter = this.head.unlinkAndOpen();

        this.tail = this.head;

        if (waiter != null) {
            waiter.wake();
        }
    }
}
