package net.gegy1000.acttwo.async;

import net.gegy1000.acttwo.util.UnsafeAccess;
import net.gegy1000.justnow.Waker;
import sun.misc.Unsafe;

public final class WaiterList {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();

    private static final long ROOT_OFFSET;

    static {
        try {
            ROOT_OFFSET = UNSAFE.objectFieldOffset(WaiterList.class.getDeclaredField("root"));
        } catch (NoSuchFieldException e) {
            throw new Error("Failed to get waiter field offsets", e);
        }
    }

    private volatile LinkedWaiter root;

    public void registerWaiter(LinkedWaiter waiter, Waker waker) {
        // this waiter object is already registered to the queue
        // we know this waiter hasn't been woken up yet if it is linked: the link is the first thing reset
        if (waiter.isLinked()) {
            return;
        }

        // initialize the waker on the waiter object
        waiter.setWaker(waker);

        while (true) {
            // try swap the root node with our node. if it fails, try again
            LinkedWaiter root = this.root;
            waiter.linkTo(root);

            if (UNSAFE.compareAndSwapObject(this, ROOT_OFFSET, root, waiter)) {
                return;
            }
        }
    }

    public void wake() {
        LinkedWaiter waiter = (LinkedWaiter) UNSAFE.getAndSetObject(this, ROOT_OFFSET, null);
        if (waiter != null) {
            waiter.wake();
        }
    }
}
