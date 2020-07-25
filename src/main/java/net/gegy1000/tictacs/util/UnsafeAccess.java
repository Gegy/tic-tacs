package net.gegy1000.tictacs.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

public final class UnsafeAccess {
    private static final Unsafe INSTANCE = getUnsafe();

    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new Error("failed to access unsafe", e);
        }
    }

    public static Unsafe get() {
        return INSTANCE;
    }
}
