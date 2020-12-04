package net.gegy1000.tictacs.chunk.future;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;

import org.jetbrains.annotations.Nullable;

public final class JoinAllArray<T> implements Future<T[]> {
    private final Future<T>[] futures;
    private final T[] results;

    public JoinAllArray(Future<T>[] futures, T[] results) {
        this.futures = futures;
        this.results = results;
    }

    @Nullable
    @Override
    public T[] poll(Waker waker) {
        return JoinAllArray.poll(waker, this.futures, this.results);
    }

    public static <T> T[] poll(Waker waker, Future<T>[] futures, T[] results) {
        boolean pending = false;

        for (int i = 0; i < futures.length; i++) {
            Future<T> future = futures[i];
            if (future == null) continue;

            T result = future.poll(waker);
            if (result != null) {
                futures[i] = null;
                results[i] = result;
            } else {
                pending = true;
            }
        }

        return pending ? null : results;
    }
}
