package net.gegy1000.tictacs.async.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.tictacs.async.LinkedWaiter;
import net.gegy1000.tictacs.async.WaiterQueue;
import net.gegy1000.tictacs.util.UnsafeAccess;
import sun.misc.Unsafe;

public final class RwLock {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();
    private static final long STATE_OFFSET;

    static {
        try {
            STATE_OFFSET = UNSAFE.objectFieldOffset(RwLock.class.getDeclaredField("state"));
        } catch (NoSuchFieldException e) {
            throw new Error("Failed to get state field offsets", e);
        }
    }

    private static final int FREE = 0;
    private static final int WRITING = -1;

    private final Read read = new Read();
    private final Write write = new Write();

    private volatile int state = FREE;

    private final WaiterQueue waiters = new WaiterQueue();

    public Lock read() {
        return this.read;
    }

    public Lock write() {
        return this.write;
    }

    boolean tryAcquireRead() {
        while (true) {
            int state = this.state;
            if (state == WRITING) {
                return false;
            }

            if (UNSAFE.compareAndSwapInt(this, STATE_OFFSET, state, state + 1)) {
                return true;
            }
        }
    }

    boolean canAcquireRead() {
        return this.state != WRITING;
    }

    boolean tryAcquireWrite() {
        return UNSAFE.compareAndSwapInt(this, STATE_OFFSET, FREE, WRITING);
    }

    boolean canAcquireWrite() {
        return this.state == FREE;
    }

    void releaseWrite() {
        if (!UNSAFE.compareAndSwapInt(this, STATE_OFFSET, WRITING, FREE)) {
            throw new IllegalStateException("write lock not acquired");
        }

        this.waiters.wake();
    }

    void releaseRead() {
        int readCount = UNSAFE.getAndAddInt(this, STATE_OFFSET, -1) - 1;
        if (readCount < 0) {
            throw new IllegalStateException("read lock not acquired");
        }

        if (readCount == FREE) {
            this.waiters.wake();
        }
    }

    @Override
    public String toString() {
        int state = this.state;
        if (state == FREE) {
            return "RwLock(FREE)";
        } else if (state == WRITING) {
            return "RwLock(WRITING)";
        } else {
            return "RwLock(READING=" + state + ")";
        }
    }

    private final class Read implements Lock {
        @Override
        public boolean tryAcquire() {
            return RwLock.this.tryAcquireRead();
        }

        @Override
        public boolean canAcquire() {
            return RwLock.this.canAcquireRead();
        }

        @Override
        public void release() {
            RwLock.this.releaseRead();
        }

        @Override
        public PollLock tryPollLock(LinkedWaiter waiter, Waker waker) {
            if (!this.tryAcquire()) {
                RwLock.this.waiters.registerWaiter(waiter, waker);
                return this.canAcquire() ? PollLock.RETRY : PollLock.PENDING;
            }

            return PollLock.ACQUIRED;
        }

        @Override
        public String toString() {
            if (this.canAcquire()) {
                return "RwLock.Read(FREE)";
            } else {
                return "RwLock.Read(LOCKED)";
            }
        }
    }

    private final class Write implements Lock {
        @Override
        public boolean tryAcquire() {
            return RwLock.this.tryAcquireWrite();
        }

        @Override
        public boolean canAcquire() {
            return RwLock.this.canAcquireWrite();
        }

        @Override
        public void release() {
            RwLock.this.releaseWrite();
        }

        @Override
        public PollLock tryPollLock(LinkedWaiter waiter, Waker waker) {
            if (!this.tryAcquire()) {
                RwLock.this.waiters.registerWaiter(waiter, waker);
                return this.canAcquire() ? PollLock.RETRY : PollLock.PENDING;
            }

            return PollLock.ACQUIRED;
        }

        @Override
        public String toString() {
            if (this.canAcquire()) {
                return "RwLock.Write(FREE)";
            } else {
                return "RwLock.Write(LOCKED)";
            }
        }
    }
}
