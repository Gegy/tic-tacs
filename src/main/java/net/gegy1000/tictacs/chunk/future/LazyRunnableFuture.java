package net.gegy1000.tictacs.chunk.future;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;

public final class LazyRunnableFuture implements Future<Unit> {
    private final Runnable runnable;

    public LazyRunnableFuture(Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    public Unit poll(Waker waker) {
        this.runnable.run();
        return Unit.INSTANCE;
    }
}
