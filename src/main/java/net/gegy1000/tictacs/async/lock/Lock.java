package net.gegy1000.tictacs.async.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.async.LinkedWaiter;

import org.jetbrains.annotations.Nullable;

public interface Lock {
    Future<Unit> READY_FUTURE = Future.ready(Unit.INSTANCE);

    boolean tryAcquire();

    boolean canAcquire();

    void release();

    PollLock tryPollLock(LinkedWaiter waiter, Waker waker);

    default Future<Unit> acquireAsync() {
        // try acquire now to avoid the allocation: this is technically bad future behaviour, but we'll allow it
        if (this.tryAcquire()) {
            return READY_FUTURE;
        }

        return new AcquireFuture(this);
    }

    final class AcquireFuture extends LinkedWaiter implements Future<Unit> {
        final Lock lock;

        public AcquireFuture(Lock lock) {
            this.lock = lock;
        }

        @Nullable
        @Override
        public Unit poll(Waker waker) {
            while (true) {
                // invalidate our waker if it is queued
                this.invalidateWaker();

                PollLock poll = this.lock.tryPollLock(this, waker);
                if (poll == PollLock.ACQUIRED) {
                    return Unit.INSTANCE;
                } else if (poll == PollLock.PENDING) {
                    return null;
                }
            }
        }
    }
}
