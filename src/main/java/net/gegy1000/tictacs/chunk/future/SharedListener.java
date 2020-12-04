package net.gegy1000.tictacs.chunk.future;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.tictacs.async.LinkedWaiter;
import net.gegy1000.tictacs.async.WaiterQueue;

import org.jetbrains.annotations.Nullable;

public abstract class SharedListener<T> implements Future<T> {
    private final WaiterQueue waiters = new WaiterQueue();

    @Nullable
    protected abstract T get();

    protected final void wake() {
        this.waiters.wake();
    }

    @Nullable
    @Override
    public final T poll(Waker waker) {
        T value = this.get();
        if (value != null) {
            return value;
        }

        LinkedWaiter waiter = new LinkedWaiter();
        this.waiters.registerWaiter(waiter, waker);

        // try get the value again in case one was set before we registered our waker
        value = this.get();
        if (value != null) {
            // if a value was set while we were registering, we can invalidate that waker now
            waiter.invalidateWaker();
            return value;
        }

        return null;
    }
}
