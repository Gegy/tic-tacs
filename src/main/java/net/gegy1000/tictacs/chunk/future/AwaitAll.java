package net.gegy1000.tictacs.chunk.future;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;

import org.jetbrains.annotations.Nullable;

public final class AwaitAll<T> implements Future<Unit> {
    private final Future<T>[] futures;

    private AwaitAll(Future<T>[] futures) {
        this.futures = futures;
    }

    public static <T> AwaitAll<T> of(Future<T>[] futures) {
        return new AwaitAll<>(futures);
    }

    @Nullable
    @Override
    public Unit poll(Waker waker) {
        return AwaitAll.poll(waker, this.futures) ? Unit.INSTANCE : null;
    }

    public static <T> boolean poll(Waker waker, Future<T>[] futures) {
        boolean ready = true;

        for (int i = 0; i < futures.length; i++) {
            Future<T> future = futures[i];
            if (future == null) continue;

            T result = future.poll(waker);
            if (result != null) {
                futures[i] = null;
            } else {
                ready = false;
            }
        }

        return ready;
    }
}
