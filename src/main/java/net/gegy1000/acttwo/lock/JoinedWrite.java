package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

import javax.annotation.Nullable;
import java.util.Arrays;

// TODO: code duplication with JoinedRead
public final class JoinedWrite<T> extends RwLock.Waiting implements Future<RwGuard<T[]>> {
    private final RwLock<T>[] locks;
    private final T[] result;

    public JoinedWrite(RwLock<T>[] locks, T[] result) {
        this.locks = locks;
        this.result = result;
    }

    private void registerWakers(Waker waker) {
        for (RwLock<T> lock : this.locks) {
            if (lock != null) {
                lock.registerWaiting(this, waker);
            }
        }
    }

    private boolean tryAcquireWrite() {
        RwLock<T>[] locks = this.locks;

        for (int i = 0; i < locks.length; i++) {
            RwLock<T> lock = locks[i];
            if (lock != null && !lock.tryAcquireWrite()) {
                // if we failed to acquire, we need to release all the locks before us
                this.releaseUpTo(i);
                break;
            }
        }

        return true;
    }

    private boolean canAcquireWrite() {
        for (RwLock<T> lock : this.locks) {
            if (lock != null && !lock.canAcquireWrite()) {
                return false;
            }
        }
        return true;
    }

    private void releaseUpTo(int end) {
        RwLock<T>[] locks = this.locks;
        for (int i = 0; i < end; i++) {
            RwLock<T> lock = locks[i];
            if (lock != null) {
                lock.releaseWrite();
            }
        }
    }

    private void releaseAll() {
        this.releaseUpTo(this.locks.length);
        Arrays.fill(this.result, null);
    }

    @Nullable
    @Override
    public RwGuard<T[]> poll(Waker waker) {
        while (true) {
            // invalidate our queued waker
            this.invalidateWaker();

            // if we are able to successfully acquire every lock, return our result
            if (this.tryAcquireWrite()) {
                return this.buildResult();
            }

            // we failed to acquire the Write lock, register our waker
            this.registerWakers(waker);

            // if anything is still locked, there's nothing more we can do for now
            if (!this.canAcquireWrite()) {
                return null;
            }
        }
    }

    private RwGuard<T[]> buildResult() {
        T[] result = this.result;
        RwLock<T>[] locks = this.locks;
        for (int i = 0; i < locks.length; i++) {
            RwLock<T> lock = locks[i];
            if (lock != null) {
                result[i] = lock.inner;
            }
        }

        return new RwGuard<T[]>() {
            @Override
            public T[] get() {
                return result;
            }

            @Override
            public void release() {
                JoinedWrite.this.releaseAll();
            }
        };
    }
}
