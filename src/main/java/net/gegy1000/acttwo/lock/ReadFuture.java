package net.gegy1000.acttwo.lock;

import net.gegy1000.justnow.future.Future;

public interface ReadFuture<T> extends Future<RwGuard<T>> {
}
