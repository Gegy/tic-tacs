package net.gegy1000.acttwo.chunk;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

import javax.annotation.Nullable;

public final class FutureHandle<T> implements Future<T> {
    private T value;

    private Waker waker;

    @Nullable
    @Override
    public synchronized T poll(Waker waker) {
        this.waker = waker;
        return this.value;
    }

    public synchronized void complete(T value) {
        this.value = value;

        Waker waker = this.waker;
        if (waker != null) {
            waker.wake();
        }
    }
}
