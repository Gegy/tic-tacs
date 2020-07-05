package net.gegy1000.acttwo.lock;

public interface WriteRwGuard<T> extends RwGuard<T> {
    void set(T value);
}
