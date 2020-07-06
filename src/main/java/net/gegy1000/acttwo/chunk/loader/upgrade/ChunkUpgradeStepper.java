package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.AtomicPool;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.future.JoinAllArray;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.AbstractList;
import java.util.Arrays;

final class ChunkUpgradeStepper {
    // TODO: measure how many are needed
    private static final AtomicPool<ContextView> CONTEXT_POOL = new AtomicPool<>(64, ContextView::new);
    private static final AtomicPool<TaskWithContext> TASK_POOL = new AtomicPool<>(64, TaskWithContext::new);

    private final ChunkUpgradeFuture parent;

    private final Future<Chunk>[] tasks;
    private final Chunk[] chunks;

    private boolean pollingTasks;

    @SuppressWarnings("unchecked")
    ChunkUpgradeStepper(ChunkUpgradeFuture parent) {
        this.parent = parent;

        int size = parent.upgradeKernel.getSize();
        this.tasks = new Future[size * size];
        this.chunks = new Chunk[size * size];
    }

    void reset() {
        this.pollingTasks = false;
        Arrays.fill(this.chunks, null);
        Arrays.fill(this.tasks, null);
    }

    @Nullable
    Chunk[] pollStep(Waker waker, ChunkEntryKernel entries, ChunkStatus status) {
        Future<Chunk>[] tasks = this.tasks;

        if (!this.pollingTasks) {
            this.pollingTasks = true;
            if (status == ChunkStatus.EMPTY) {
                this.openLoadTasks(entries, tasks);
            } else {
                this.openUpgradeTasks(entries, status, tasks);
            }
        }

        return JoinAllArray.poll(waker, tasks, this.chunks);
    }

    private void openUpgradeTasks(ChunkEntryKernel entries, ChunkStatus status, Future<Chunk>[] tasks) {
        entries.openWriteTasks(tasks, entry -> this.upgradeChunk(entry, entries, status));
    }

    private void openLoadTasks(ChunkEntryKernel entries, Future<Chunk>[] tasks) {
        entries.openWriteTasks(tasks, this::loadChunk);
    }

    private Future<Chunk> upgradeChunk(ChunkEntryState entry, ChunkEntryKernel entryKernel, ChunkStatus status) {
        ContextView context = this.openContext(entry, entryKernel, status);

        Future<Chunk> future = this.parent.controller.upgrader.runUpgradeTask(status, context);

        // if we're upgrading to the full status, we need to run the finalize task too
        if (status == ChunkStatus.FULL) {
            ChunkUpgrader upgrader = this.parent.controller.upgrader;
            future = future.andThen(chunk -> upgrader.runFinalizeTask(entry, ChunkStatus.FULL, chunk));
        }

        return this.createTaskWithContext(future, context);
    }

    private ContextView openContext(ChunkEntryState entry, ChunkEntryKernel entryKernel, ChunkStatus status) {
        ContextView context = CONTEXT_POOL.acquire();
        ChunkPos targetPos = entry.getPos();
        int targetRadius = status.getTaskMargin();

        context.open(this.parent.pos, entryKernel, targetPos, targetRadius);

        return context;
    }

    private TaskWithContext createTaskWithContext(Future<Chunk> future, ContextView context) {
        TaskWithContext task = TASK_POOL.acquire();
        task.future = future;
        task.context = context;

        return task;
    }

    private Future<Chunk> loadChunk(ChunkEntryState entry) {
        return this.parent.controller.loader.spawnLoadChunk(entry.parent);
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
        private ChunkEntryState[] source;
        private int sourceSize;
        private int targetSize;

        private int targetToSourceOffsetX;
        private int targetToSourceOffsetZ;

        void open(
                ChunkPos sourceOrigin, ChunkEntryKernel entryKernel,
                ChunkPos targetOrigin, int targetRadius
        ) {
            int sourceRadius = entryKernel.getRadius();
            this.source = entryKernel.getEntries();

            this.sourceSize = sourceRadius * 2 + 1;
            this.targetSize = targetRadius * 2 + 1;

            this.targetToSourceOffsetX = (targetOrigin.x - targetRadius) - (sourceOrigin.x - sourceRadius);
            this.targetToSourceOffsetZ = (targetOrigin.z - targetRadius) - (sourceOrigin.z - sourceRadius);
        }

        @Override
        public Chunk get(int targetIndex) {
            int targetX = targetIndex % this.targetSize;
            int targetZ = targetIndex / this.targetSize;
            int sourceX = targetX + this.targetToSourceOffsetX;
            int sourceZ = targetZ + this.targetToSourceOffsetZ;

            int sourceIndex = sourceX + sourceZ * this.sourceSize;

            if (sourceIndex < 0 || sourceIndex >= this.source.length) {
                System.out.println(sourceIndex);
            }

            return this.source[sourceIndex].getChunk();
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
