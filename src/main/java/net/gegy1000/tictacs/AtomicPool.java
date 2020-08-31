package net.gegy1000.tictacs;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

public final class AtomicPool<T> {
    private final int capacity;

    private final AtomicReferenceArray<T> array;
    private final AtomicInteger pointer = new AtomicInteger(-1);

    private final Supplier<T> supplier;

    public AtomicPool(int capacity, Supplier<T> supplier) {
        this.capacity = capacity;
        this.array = new AtomicReferenceArray<>(capacity);
        this.supplier = supplier;
    }

    public T acquire() {
        while (true) {
            int pointer = this.pointer.get();

            // we've fallen outside the pool: allocate a new entry
            if (pointer < 0) {
                return this.supplier.get();
            }

            if (this.pointer.compareAndSet(pointer, pointer - 1)) {
                T value = this.array.getAndSet(pointer, null);
                if (value == null) {
                    // this value hasn't been set yet: try again
                    continue;
                }
                return value;
            }
        }
    }

    public void release(T object) {
        while (true) {
            int pointer = this.pointer.get();
            int newPointer = pointer + 1;

            // the pool is full, we don't need to return this object
            if (newPointer >= this.capacity) {
                return;
            }

            if (this.pointer.compareAndSet(pointer, newPointer)) {
                this.array.set(newPointer, object);
                return;
            }
        }
    }
}
