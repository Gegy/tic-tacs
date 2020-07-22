package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.ChunkAccess;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.step.ChunkMargin;
import net.gegy1000.acttwo.chunk.step.ChunkStep;
import net.gegy1000.acttwo.lock.JoinedAcquire;
import net.gegy1000.acttwo.lock.RwGuard;
import net.gegy1000.acttwo.lock.RwLock;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Function;

final class AcquireChunks implements Future<AcquireChunks.Result> {
    private final ChunkUpgradeKernel kernel;
    private final ChunkMap chunkMap;

    private final Result result;

    private ChunkPos pos;
    private ChunkStep currentStep;

    private final BitSet upgradeChunks;

    private final RwLock<ChunkEntryState>[] writerLocks;
    private final RwLock<ChunkEntryState>[] readerLocks;

    private final JoinedAcquire acquireWrite = JoinedAcquire.write();
    private final JoinedAcquire acquireRead = JoinedAcquire.read();

    private RwGuard<ChunkEntryState[]> writerGuard;
    private RwGuard<ChunkEntryState[]> readerGuard;

    private boolean ready;

    @SuppressWarnings("unchecked")
    public AcquireChunks(ChunkUpgradeKernel kernel, ChunkMap chunkMap) {
        this.kernel = kernel;

        this.chunkMap = chunkMap;
        this.upgradeChunks = kernel.create(BitSet::new);
        this.writerLocks = kernel.create(RwLock[]::new);
        this.readerLocks = kernel.create(RwLock[]::new);

        this.result = kernel.create(Result::new);
    }

    public void prepare(ChunkPos pos, ChunkStep currentStep) {
        this.ready = true;
        this.pos = pos;
        this.currentStep = currentStep;
    }

    private void collectUpgradeLocks(RwLock<ChunkEntryState>[] locks) {
        ChunkAccess chunks = this.chunkMap.visible();

        ChunkPos pos = this.pos;
        ChunkUpgradeKernel kernel = this.kernel;

        ChunkStep step = this.currentStep;
        int radiusForStep = this.kernel.getRadiusFor(step);

        for (int z = -radiusForStep; z <= radiusForStep; z++) {
            for (int x = -radiusForStep; x <= radiusForStep; x++) {
                ChunkEntry entry = chunks.getEntry(x + pos.x, z + pos.z);
                if (entry != null && entry.canUpgradeTo(step)) {
                    int idx = kernel.index(x, z);
                    locks[idx] = entry.getState();
                    this.upgradeChunks.set(idx);
                }
            }
        }
    }

    private void collectContextLocks(RwLock<ChunkEntryState>[] locks, int margin) {
        int radius = this.kernel.getRadiusFor(this.currentStep);
        ChunkUpgradeKernel kernel = this.kernel;

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = kernel.index(x, z);
                if (this.upgradeChunks.get(idx)) {
                    this.addContextMargin(x, z, margin, locks);
                }
            }
        }
    }

    private void addContextMargin(int centerX, int centerZ, int margin, RwLock<ChunkEntryState>[] locks) {
        ChunkAccess chunks = this.chunkMap.visible();

        ChunkPos pos = this.pos;
        ChunkUpgradeKernel kernel = this.kernel;
        int radius = kernel.getRadius();

        int minX = Math.max(centerX - margin, -radius);
        int maxX = Math.min(centerX + margin, radius);
        int minZ = Math.max(centerZ - margin, -radius);
        int maxZ = Math.min(centerZ + margin, radius);

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int idx = kernel.index(x, z);

                if (locks[idx] == null && !this.upgradeChunks.get(idx)) {
                    ChunkEntry entry = chunks.getEntry(x + pos.x, z + pos.z);
                    if (entry != null) {
                        locks[idx] = entry.getState();
                    }
                }
            }
        }
    }

    @Nullable
    @Override
    public Result poll(Waker waker) {
        if (!this.pollWriters(waker)) {
            return null;
        }

        if (!this.pollReaders(waker)) {
            this.writerGuard.release();
            this.writerGuard = null;
            return null;
        }

        return this.result;
    }

    private boolean pollWriters(Waker waker) {
        if (this.writerGuard == null) {
            Arrays.fill(this.writerLocks, null);
            this.upgradeChunks.clear();

            this.collectUpgradeLocks(this.writerLocks);

            ChunkMargin margin = this.currentStep.getMargin();
            if (!margin.isEmpty() && margin.write) {
                this.collectContextLocks(this.writerLocks, margin.radius);
            }

            this.writerGuard = this.acquireWrite.poll(waker, this.writerLocks, this.result.entries);
        }

        return this.writerGuard != null;
    }

    private boolean pollReaders(Waker waker) {
        ChunkMargin margin = this.currentStep.getMargin();
        if (margin.isEmpty() || margin.write) {
            // no readers are required
            return true;
        }

        if (this.readerGuard == null) {
            Arrays.fill(this.readerLocks, null);
            this.collectContextLocks(this.readerLocks, margin.radius);

            this.readerGuard = this.acquireRead.poll(waker, this.readerLocks, this.result.entries);
        }

        return this.readerGuard != null;
    }

    public boolean isReady() {
        return this.ready;
    }

    public void release() {
        this.ready = false;

        this.pos = null;
        this.currentStep = null;

        if (this.writerGuard != null) {
            this.writerGuard.release();
            this.writerGuard = null;
        }

        if (this.readerGuard != null) {
            this.readerGuard.release();
            this.readerGuard = null;
        }
    }

    public final class Result {
        final ChunkEntryState[] entries;

        Result(int bufferSize) {
            this.entries = new ChunkEntryState[bufferSize];
        }

        public <T> void openUpgradeTasks(Future<T>[] tasks, Function<ChunkEntryState, Future<T>> function) {
            ChunkEntryState[] entries = this.entries;

            for (int i = 0; i < entries.length; i++) {
                ChunkEntryState entry = entries[i];
                if (entry != null && AcquireChunks.this.upgradeChunks.get(i)) {
                    tasks[i] = function.apply(entry);
                }
            }
        }

        @Nullable
        public ChunkEntryState getEntry(int x, int z) {
            int idx = AcquireChunks.this.kernel.index(x, z);
            return this.entries[idx];
        }
    }
}
