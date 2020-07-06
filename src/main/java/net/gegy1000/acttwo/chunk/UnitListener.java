package net.gegy1000.acttwo.chunk;

import net.gegy1000.justnow.tuple.Unit;

import javax.annotation.Nullable;

public final class UnitListener extends SharedListener<Unit> {
    private volatile Unit result;

    @Nullable
    @Override
    protected Unit get() {
        return this.result;
    }

    public void complete() {
        this.result = Unit.INSTANCE;
        this.wake();
    }

    public void reset() {
        this.result = null;
    }
}
