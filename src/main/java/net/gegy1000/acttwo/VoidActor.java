package net.gegy1000.acttwo;

import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.util.thread.TaskQueue;

import javax.annotation.Nullable;

public final class VoidActor extends TaskExecutor<Runnable> {
    public static final VoidActor INSTANCE = new VoidActor();

    private VoidActor() {
        super(new VoidQueue(), runnable -> {}, "void");
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
