package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.tictacs.AtomicPool;
import net.gegy1000.tictacs.chunk.entry.ChunkEntryState;
import net.gegy1000.tictacs.chunk.future.JoinAllArray;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.AbstractList;
import java.util.Arrays;

final class ChunkUpgradeStepper {
    private static final AtomicPool<ContextView> CONTEXT_POOL = new AtomicPool<>(512, ContextView::new);
    private static final AtomicPool<TaskWithContext> TASK_POOL = new AtomicPool<>(512, TaskWithContext::new);

    private final ChunkUpgradeFuture parent;

    private final Future<Chunk>[] tasks;
    private final Chunk[] chunks;

    private volatile boolean pollingTasks;

    @SuppressWarnings("unchecked")
    ChunkUpgradeStepper(ChunkUpgradeFuture parent) {
        this.parent = parent;

        ChunkUpgradeKernel kernel = ChunkUpgradeKernel.forStep(parent.targetStep);
        this.tasks = kernel.create(Future[]::new);
        this.chunks = kernel.create(Chunk[]::new);
    }

    void reset() {
        this.pollingTasks = false;
        Arrays.fill(this.chunks, null);
        Arrays.fill(this.tasks, null);
    }

    @Nullable
    Chunk[] pollStep(Waker waker, AcquireChunks.Result chunks, ChunkStep step) {
        Future<Chunk>[] tasks = this.tasks;

        if (!this.pollingTasks) {
            this.pollingTasks = true;
            if (step == ChunkStep.EMPTY) {
                this.openLoadTasks(chunks, tasks);
            } else {
                this.openUpgradeTasks(chunks, step, tasks);
            }
        }

        return JoinAllArray.poll(waker, tasks, this.chunks);
    }

    private void openUpgradeTasks(AcquireChunks.Result chunks, ChunkStep step, Future<Chunk>[] tasks) {
        chunks.openUpgradeTasks(tasks, entry -> this.upgradeChunk(entry, chunks, step));
    }

    private void openLoadTasks(AcquireChunks.Result chunks, Future<Chunk>[] tasks) {
        chunks.openUpgradeTasks(tasks, this::loadChunk);
    }

    private Future<Chunk> upgradeChunk(ChunkEntryState entry, AcquireChunks.Result chunks, ChunkStep step) {
        ContextView context = this.openContext(entry, chunks, step);

        Future<Chunk> future = this.parent.controller.getUpgrader().runStepTask(entry, step, context);
        return this.createTaskWithContext(future, context);
    }

    private ContextView openContext(ChunkEntryState entry, AcquireChunks.Result chunks, ChunkStep step) {
        ContextView context = CONTEXT_POOL.acquire();
        ChunkPos targetPos = entry.getPos();

        int targetRadius = step.getRequirements().getRadius();
        context.open(this.parent.pos, chunks, targetPos, targetRadius);

        return context;
    }

    private TaskWithContext createTaskWithContext(Future<Chunk> future, ContextView context) {
        TaskWithContext task = TASK_POOL.acquire();
        task.future = future;
        task.context = context;

        return task;
    }

    private Future<Chunk> loadChunk(ChunkEntryState entry) {
        return this.parent.controller.spawnLoadChunk(entry.parent);
    }

    static class TaskWithContext implements Future<Chunk> {
        Future<Chunk> future;
        ContextView context;

        @Nullable
        @Override
        public Chunk poll(Waker waker) {
            Chunk poll = this.future.poll(waker);
            if (poll != null) {
                this.release();
                return poll;
            }

            return null;
        }

        void release() {
            this.context.release();

            this.future = null;
            this.context = null;

            TASK_POOL.release(this);
        }
    }

    static class ContextView extends AbstractList<Chunk> {
        private AcquireChunks.Result source;
        private int targetSize;

        private int targetToSourceOffsetX;
        private int targetToSourceOffsetZ;

        void open(
                ChunkPos sourceOrigin, AcquireChunks.Result chunks,
                ChunkPos targetOrigin, int targetRadius
        ) {
            this.source = chunks;
            this.targetSize = targetRadius * 2 + 1;

            this.targetToSourceOffsetX = (targetOrigin.x - targetRadius) - sourceOrigin.x;
            this.targetToSourceOffsetZ = (targetOrigin.z - targetRadius) - sourceOrigin.z;

            Chunk chunk = this.get(this.size() / 2);
            if (!chunk.getPos().equals(targetOrigin)) {
                throw new IllegalStateException("center chunk pos does not match target pos");
            }
        }

        @Override
        public Chunk get(int targetIndex) {
            int targetX = targetIndex % this.targetSize;
            int targetZ = targetIndex / this.targetSize;
            int sourceX = targetX + this.targetToSourceOffsetX;
            int sourceZ = targetZ + this.targetToSourceOffsetZ;

            ChunkEntryState entry = this.source.getEntry(sourceX, sourceZ);
            if (entry == null) {
                return null;
            }

            return entry.getChunk();
        }

        @Override
        public int size() {
            return this.targetSize * this.targetSize;
        }

        void release() {
            this.source = null;
            CONTEXT_POOL.release(this);
        }
    }
}
