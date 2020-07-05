package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;

public interface WriteFuture<T> extends Future<WriteRwGuard<T>> {
    default Future<Unit> write(T value) {
        return this.map(access -> {
            access.set(value);
            access.release();
            return Unit.INSTANCE;
        });
    }
}
