package net.gegy1000.tictacs.async;

import net.gegy1000.justnow.Waker;
import net.gegy1000.tictacs.util.UnsafeAccess;
import sun.misc.Unsafe;

import org.jetbrains.annotations.Nullable;

public class LinkedWaiter {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();

    private static final long WAKER_OFFSET;
    private static final long NEXT_OFFSET;

    static {
        try {
            WAKER_OFFSET = UNSAFE.objectFieldOffset(LinkedWaiter.class.getDeclaredField("waker"));
            NEXT_OFFSET = UNSAFE.objectFieldOffset(LinkedWaiter.class.getDeclaredField("next"));
        } catch (NoSuchFieldException e) {
            throw new Error("Failed to get waiter field offsets", e);
        }
    }

    private volatile Waker waker;
    private volatile LinkedWaiter next = this.closed();

    final void setWaker(Waker waker) {
        this.waker = waker;
    }

    final boolean tryLink(LinkedWaiter next) {
        return UNSAFE.compareAndSwapObject(this, NEXT_OFFSET, this.open(), next);
    }

    final boolean tryOpenLink() {
        return UNSAFE.compareAndSwapObject(this, NEXT_OFFSET, this.closed(), this.open());
    }

    final void setLink(LinkedWaiter next) {
        this.next = next;
    }

    @Nullable
    final LinkedWaiter unlinkAndClose() {
        return (LinkedWaiter) UNSAFE.getAndSetObject(this, NEXT_OFFSET, this.closed());
    }

    void wake() {
        LinkedWaiter waiter = this;
        while (waiter != null) {
            waiter = waiter.wakeSelf();
        }
    }

    @Nullable
    LinkedWaiter wakeSelf() {
        LinkedWaiter next = this.unlinkAndClose();
        Waker waker = (Waker) UNSAFE.getAndSetObject(this, WAKER_OFFSET, null);

        if (waker != null) {
            waker.wake();
        }

        return next;
    }

    public final void invalidateWaker() {
        this.waker = null;
    }

    final boolean isClosed(LinkedWaiter waiter) {
        return waiter == this.closed();
    }

    private LinkedWaiter open() {
        return null;
    }

    private LinkedWaiter closed() {
        return this;
    }
}
