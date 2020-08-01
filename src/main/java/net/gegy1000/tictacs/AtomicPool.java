package net.gegy1000.tictacs;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class AtomicPool<T> {
    private final int capacity;

    private final Object[] array;
    private final AtomicInteger pointer = new AtomicInteger(-1);

    private final Supplier<T> supplier;

    public AtomicPool(int capacity, Supplier<T> supplier) {
        this.capacity = capacity;
        this.array = new Object[capacity];
        this.supplier = supplier;
    }

    @SuppressWarnings("unchecked")
    public T acquire() {
        while (true) {
            int pointer = this.pointer.get();
            if (pointer < 0) {
                return this.supplier.get();
            }

            if (this.pointer.compareAndSet(pointer, pointer - 1)) {
                return (T) this.array[pointer];
            }
        }
    }

    public void release(T object) {
        while (true) {
            int pointer = this.pointer.get();
            int newPointer = pointer + 1;

            if (newPointer >= this.capacity) {
                return;
            }

            if (this.pointer.compareAndSet(pointer, newPointer)) {
                this.array[newPointer] = object;
                return;
            }
        }
    }
}
