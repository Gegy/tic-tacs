package net.gegy1000.acttwo.async.lock;

public final class LockGuard<T> implements AutoCloseable {
    private final Lock lock;
    private final T value;

    public LockGuard(Lock lock, T value) {
        this.lock = lock;
        this.value = value;
    }

    public T get() {
        return this.value;
    }

    public void release() {
        this.lock.release();
    }

    @Override
    public void close() {
        this.release();
    }
}
