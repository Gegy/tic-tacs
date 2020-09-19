package net.gegy1000.tictacs.chunk;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

import javax.annotation.Nullable;

public final class FutureHandle<T> implements Future<T> {
    private volatile T value;
    private volatile Waker waker;

    @Nullable
    @Override
    public T poll(Waker waker) {
        this.waker = waker;
        return this.value;
    }

    public void complete(T value) {
        this.value = value;

        Waker waker = this.waker;
        if (waker != null) {
            waker.wake();
        }
    }
}
