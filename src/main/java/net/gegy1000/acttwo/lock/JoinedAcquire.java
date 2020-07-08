package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;

import javax.annotation.Nullable;
import java.util.Arrays;

public abstract class JoinedAcquire extends RwLock.Waiting {
    public static JoinedAcquire read() {
        return new JoinedAcquire() {
            @Override
            protected <T> boolean tryAcquireLock(RwLock<T> lock) {
                return lock.tryAcquireRead();
            }

            @Override
            protected <T> boolean canAcquireLock(RwLock<T> lock) {
                return lock.canAcquireRead();
            }

            @Override
            protected <T> void releaseLock(RwLock<T> lock) {
                lock.releaseRead();
            }
        };
    }

    public static JoinedAcquire write() {
        return new JoinedAcquire() {
            @Override
            protected <T> boolean tryAcquireLock(RwLock<T> lock) {
                return lock.tryAcquireWrite();
            }

            @Override
            protected <T> boolean canAcquireLock(RwLock<T> lock) {
                return lock.canAcquireWrite();
            }

            @Override
            protected <T> void releaseLock(RwLock<T> lock) {
                lock.releaseWrite();
            }
        };
    }

    protected abstract <T> boolean tryAcquireLock(RwLock<T> lock);

    protected abstract <T> boolean canAcquireLock(RwLock<T> lock);

    protected abstract <T> void releaseLock(RwLock<T> lock);

    @Nullable
    private <T> RwLock<T> tryAcquireLocks(RwLock<T>[] locks) {
        for (int i = 0; i < locks.length; i++) {
            RwLock<T> lock = locks[i];
            if (lock != null && !this.tryAcquireLock(lock)) {
                // if we failed to acquire, we need to release all the locks before us
                this.releaseUpTo(locks, i);
                return lock;
            }
        }

        return null;
    }

    @Nullable
    public final <T> RwGuard<T[]> poll(Waker waker, RwLock<T>[] locks, T[] result) {
        while (true) {
            // invalidate our queued waker
            this.invalidateWaker();

            // if we are able to successfully acquire every lock, return our result
            RwLock<T> blockingLock = this.tryAcquireLocks(locks);
            if (blockingLock == null) {
                return this.makeResult(locks, result);
            }

            // we failed to acquire the locks, register our waker
            blockingLock.registerWaiting(this, waker);

            // if we're still blocked, there's nothing more we can do for now
            if (!this.canAcquireLock(blockingLock)) {
                return null;
            }
        }
    }

    private <T> RwGuard<T[]> makeResult(RwLock<T>[] locks, T[] result) {
        for (int i = 0; i < locks.length; i++) {
            RwLock<T> lock = locks[i];
            if (lock != null) {
                result[i] = lock.getInnerUnsafe();
            }
        }

        return new RwGuard<T[]>() {
            @Override
            public T[] get() {
                return result;
            }

            @Override
            public void release() {
                for (RwLock<T> lock : locks) {
                    if (lock != null) {
                        JoinedAcquire.this.releaseLock(lock);
                    }
                }
                Arrays.fill(locks, null);
                Arrays.fill(result, null);
            }
        };
    }

    private <T> void releaseUpTo(RwLock<T>[] locks, int end) {
        for (int i = 0; i < end; i++) {
            RwLock<T> lock = locks[i];
            if (lock != null) {
                this.releaseLock(lock);
            }
        }
    }
}
