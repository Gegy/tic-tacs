package net.gegy1000.acttwo.chunk.future;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;

import javax.annotation.Nullable;
import java.util.concurrent.CompletionException;

public final class AwaitAll<T> implements Future<Unit> {
    private final Future<T>[] futures;
    private int completedCount;

    public AwaitAll(Future<T>[] futures) {
        this.futures = futures;
    }

    @Nullable
    @Override
    public Unit poll(Waker waker) {
        for (int i = 0; i < this.futures.length; i++) {
            Future<T> future = this.futures[i];
            if (future == null) continue;

            T result = future.poll(waker);
            if (result != null) {
                this.futures[i] = null;
                this.tryRelease(result);

                if (++this.completedCount >= this.futures.length) {
                    return Unit.INSTANCE;
                }
            }
        }

        return null;
    }

    private void tryRelease(T poll) {
        if (poll instanceof AutoCloseable) {
            try {
                ((AutoCloseable) poll).close();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }
    }
}
