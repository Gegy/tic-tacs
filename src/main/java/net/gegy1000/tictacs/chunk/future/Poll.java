package net.gegy1000.tictacs.chunk.future;

import javax.annotation.Nullable;

public final class Poll<T> {
    private static final Poll<?> PENDING = new Poll<>(null, true);

    private final T ready;
    private final boolean pending;

    private Poll(T ready, boolean pending) {
        this.ready = ready;
        this.pending = pending;
    }

    public static <T> Poll<T> ready(T value) {
        return new Poll<>(value, false);
    }

    @SuppressWarnings("unchecked")
    public static <T> Poll<T> pending() {
        return (Poll<T>) PENDING;
    }

    @Nullable
    public T get() {
        return this.ready;
    }

    public boolean isPending() {
        return this.pending;
    }
}
