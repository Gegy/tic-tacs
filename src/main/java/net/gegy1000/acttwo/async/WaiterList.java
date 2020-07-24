package net.gegy1000.acttwo.async;

import net.gegy1000.justnow.Waker;

import java.util.concurrent.atomic.AtomicReference;

public final class WaiterList {
    private final AtomicReference<LinkedWaiter> root = new AtomicReference<>();

    public void registerWaiter(LinkedWaiter waiter, Waker waker) {
        // initialize the waker on the waiter object
        waiter.setWaker(waker);

        // this waiter object is already registered to the queue
        if (waiter.isLinked()) {
            return;
        }

        while (true) {
            // try swap the root node with our node. if it fails, try again
            LinkedWaiter root = this.root.get();

            // this waiter object is already registered to the queue
            if (root == waiter) {
                return;
            }

            waiter.linkTo(root);

            if (this.root.compareAndSet(root, waiter)) {
                return;
            }
        }
    }

    public void wake() {
        LinkedWaiter waiter = this.root.getAndSet(null);
        if (waiter != null) {
            waiter.wake();
        }
    }
}
