package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;

import javax.annotation.Nullable;

public interface Lock {
    boolean tryAcquire();

    boolean canAcquire();

    void release();

    boolean tryAcquireAsync(LockWaiter waiter, Waker waker);

    static Future<Unit> acquireAsync(Lock lock) {
        return new AcquireFuture(lock);
    }

    final class AcquireFuture extends LockWaiter implements Future<Unit> {
        final Lock lock;

        AcquireFuture(Lock lock) {
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
