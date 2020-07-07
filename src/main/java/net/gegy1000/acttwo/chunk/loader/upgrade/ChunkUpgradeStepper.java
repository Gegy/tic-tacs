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
    Chunk[] pollStep(Waker waker, AcquiredChunks chunks, ChunkStatus status) {
        Future<Chunk>[] tasks = this.tasks;

        if (!this.pollingTasks) {
            this.pollingTasks = true;
            if (status == ChunkStatus.EMPTY) {
                this.openLoadTasks(chunks, tasks);
            } else {
                this.openUpgradeTasks(chunks, status, tasks);
            }
        }

        return JoinAllArray.poll(waker, tasks, this.chunks);
    }

    private void openUpgradeTasks(AcquiredChunks chunks, ChunkStatus status, Future<Chunk>[] tasks) {
        chunks.openWriteTasks(tasks, entry -> this.upgradeChunk(entry, chunks, status));
    }

    private void openLoadTasks(AcquiredChunks chunks, Future<Chunk>[] tasks) {
        chunks.openWriteTasks(tasks, this::loadChunk);
    }

    private Future<Chunk> upgradeChunk(ChunkEntryState entry, AcquiredChunks chunks, ChunkStatus status) {
        ContextView context = this.openContext(entry, chunks, status);

        Future<Chunk> future = this.parent.controller.upgrader.runUpgradeTask(entry, status, context);
        return this.createTaskWithContext(future, context);
    }

    private ContextView openContext(ChunkEntryState entry, AcquiredChunks chunks, ChunkStatus status) {
        ContextView context = CONTEXT_POOL.acquire();
        ChunkPos targetPos = entry.getPos();
        int targetRadius = status.getTaskMargin();

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
        private AcquiredChunks source;
        private int targetSize;

        private int targetToSourceOffsetX;
        private int targetToSourceOffsetZ;

        void open(
                ChunkPos sourceOrigin, AcquiredChunks chunks,
                ChunkPos targetOrigin, int targetRadius
        ) {
            this.source = chunks;
            this.targetSize = targetRadius * 2 + 1;

            this.targetToSourceOffsetX = (targetOrigin.x - targetRadius) - sourceOrigin.x;
            this.targetToSourceOffsetZ = (targetOrigin.z - targetRadius) - sourceOrigin.z;
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
