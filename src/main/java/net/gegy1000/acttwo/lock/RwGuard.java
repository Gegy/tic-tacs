package net.gegy1000.acttwo.lock;

import java.util.function.Function;

public interface RwGuard<T> extends AutoCloseable {
    T get();

    void release();

    @Override
    default void close() {
        this.release();
    }

    default <U> RwGuard<U> map(Function<T, U> map) {
        return new RwGuard<U>() {
            @Override
            public U get() {
                return map.apply(RwGuard.this.get());
            }

            @Override
            public void release() {
                RwGuard.this.release();
            }
        };
    }
}
