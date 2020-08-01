package net.gegy1000.tictacs.async.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.async.LinkedWaiter;

import javax.annotation.Nullable;

public interface Lock {
    Future<Unit> READY_FUTURE = Future.ready(Unit.INSTANCE);

    boolean tryAcquire();

    boolean canAcquire();

    void release();

    boolean tryAcquireAsync(LinkedWaiter waiter, Waker waker);

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

                // try to acquire the lock and return if successful
                if (this.lock.tryAcquireAsync(this, waker)) {
                    return Unit.INSTANCE;
                }

                // if the lock is still acquired, there's nothing more to be done for now
                if (!this.lock.canAcquire()) {
                    return null;
                }
            }
        }
    }
}
