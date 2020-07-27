package net.gegy1000.tictacs.async.worker;

import net.gegy1000.justnow.Waker;
import net.gegy1000.tictacs.util.UnsafeAccess;
import sun.misc.Unsafe;

public final class ChunkWaker implements Waker {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();
    private static final long STATE_OFFSET;

    static {
        try {
            STATE_OFFSET = UNSAFE.objectFieldOffset(ChunkWaker.class.getDeclaredField("state"));
        } catch (NoSuchFieldException e) {
            throw new Error("failed to get state offset", e);
        }
    }

    private static final int WAITING = 0;
    private static final int POLLING = 1;
    private static final int AWOKEN = 2;

    private final ChunkTask<?> task;

    private volatile int state = AWOKEN;

    ChunkWaker(ChunkTask<?> task) {
        this.task = task;
    }

    @Override
    public void wake() {
        int prevState = UNSAFE.getAndSetInt(this, STATE_OFFSET, AWOKEN);

        // only enqueue the task if we're still waiting for a signal
        if (prevState == WAITING) {
            this.task.enqueue();
        }
    }

    void polling() {
        this.state = POLLING;
    }

    void ready() {
        // we didn't get a result: set state to waiting. we expect state to still be polling, so if that's *not*
        // the case, we must've been awoken during polling. now that we know this task needs to continue
        // execution, we can re-enqueue it.
        if (!UNSAFE.compareAndSwapInt(this, STATE_OFFSET, POLLING, WAITING)) {
            this.task.enqueue();
        }
    }
}
