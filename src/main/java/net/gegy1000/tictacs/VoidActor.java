package net.gegy1000.tictacs;

import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.util.thread.TaskQueue;

import org.jetbrains.annotations.Nullable;

public final class VoidActor extends TaskExecutor<Runnable> {
    public VoidActor(String name) {
        super(new VoidQueue(), runnable -> {}, name);
    }

    @Override
    public void run() {
    }

    @Override
    public void send(Runnable message) {
    }

    @Override
    public void close() {
    }

    private static class VoidQueue implements TaskQueue<Runnable, Runnable> {
        @Nullable
        @Override
        public Runnable poll() {
            return null;
        }

        @Override
        public boolean add(Runnable message) {
            return false;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }
    }
}
