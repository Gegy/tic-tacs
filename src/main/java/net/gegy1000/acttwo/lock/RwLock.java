package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

public interface RwLock<T> {
    int FREE = 0;
    int WRITING = -1;

    static <T> RwLock<T> create(T inner) {
        return new SimpleRwLock<>(inner);
    }

    Future<RwGuard<T>> read();

    Future<WriteRwGuard<T>> write();

    void setInnerUnsafe(T inner);

    T getInnerUnsafe();

    boolean tryAcquireRead();

    boolean canAcquireRead();

    boolean tryAcquireWrite();

    boolean canAcquireWrite();

    void releaseWrite();

    void releaseRead();

    void registerWaiting(Waiting waiting, Waker waker);

    class Waiting {
        // we don't need atomic here because they can only be modified by their owner
        volatile Waker waker;
        volatile Waiting previous;

        void wake() {
            Waiting previous = this.previous;
            if (previous != null) {
                this.previous = null;
                previous.wake();
            }

            Waker waker = this.waker;
            if (waker != null) {
                this.waker = null;
                waker.wake();
            }
        }

        void invalidateWaker() {
            this.waker = null;
        }
    }

    final class Read<T> implements RwGuard<T> {
        private final RwLock<T> lock;

        public Read(RwLock<T> lock) {
            this.lock = lock;
        }

        @Override
        public T get() {
            return this.lock.getInnerUnsafe();
        }

        @Override
        public void release() {
            this.lock.releaseRead();
        }
    }

    final class Write<T> implements WriteRwGuard<T> {
        private final RwLock<T> lock;

        public Write(RwLock<T> lock) {
            this.lock = lock;
        }

        @Override
        public void set(T value) {
            this.lock.setInnerUnsafe(value);
        }

        @Override
        public T get() {
            return this.lock.getInnerUnsafe();
        }

        @Override
        public void release() {
            this.lock.releaseWrite();
        }
    }
}
