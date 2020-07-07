package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.ChunkAccess;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.lock.JoinedAcquire;
import net.gegy1000.acttwo.lock.RwGuard;
import net.gegy1000.acttwo.lock.RwLock;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.Arrays;

final class AcquireChunkEntries implements Future<AcquiredChunks> {
    private final ChunkUpgradeKernel kernel;
    private final ChunkMap chunkMap;

    private final AcquiredChunks acquiredChunks;

    private ChunkPos pos;
    private ChunkStatus currentStep;
    private int radiusForStep;

    private final RwLock<ChunkEntryState>[] writerLocks;
    private final RwLock<ChunkEntryState>[] readerLocks;

    private final JoinedAcquire acquireWrite = JoinedAcquire.write();
    private final JoinedAcquire acquireRead = JoinedAcquire.read();

    private RwGuard<ChunkEntryState[]> writerGuard;
    private RwGuard<ChunkEntryState[]> readerGuard;

    private boolean ready;

    @SuppressWarnings("unchecked")
    public AcquireChunkEntries(ChunkUpgradeKernel kernel, ChunkMap chunkMap) {
        this.kernel = kernel;
        this.chunkMap = chunkMap;

        int size = this.kernel.getSize();
        this.writerLocks = new RwLock[size * size];
        this.readerLocks = new RwLock[size * size];

        this.acquiredChunks = new AcquiredChunks(kernel);
    }

    public void prepare(ChunkPos pos, ChunkStatus currentStep) {
        this.ready = true;

        this.pos = pos;
        this.currentStep = currentStep;

        int radius = this.kernel.getRadius();

        // TODO: do this better
        int currentRadius = 0;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkStatus targetStatus = this.kernel.get(x, z);
                if (targetStatus.isAtLeast(currentStep)) {
                    int distance = Math.max(Math.abs(x), Math.abs(z));
                    if (distance > currentRadius) {
                        currentRadius = distance;
                    }
                }
            }
        }

        this.radiusForStep = currentRadius;
    }

    private RwLock<ChunkEntryState>[] collectWriterLocks() {
        Arrays.fill(this.writerLocks, null);

        ChunkAccess chunks = this.chunkMap.visible();

        ChunkPos pos = this.pos;
        ChunkUpgradeKernel kernel = this.kernel;

        int radiusForStep = this.radiusForStep;
        ChunkStatus status = this.currentStep;

        for (int z = -radiusForStep; z <= radiusForStep; z++) {
            for (int x = -radiusForStep; x <= radiusForStep; x++) {
                ChunkEntry entry = chunks.expectEntry(x + pos.x, z + pos.z);
                if (entry.canUpgradeTo(status)) {
                    this.writerLocks[kernel.index(x, z)] = entry.getState();
                }
            }
        }

        return this.writerLocks;
    }

    private RwLock<ChunkEntryState>[] collectReaderLocks() {
        Arrays.fill(this.readerLocks, null);

        ChunkUpgradeKernel kernel = this.kernel;

        int radius = this.radiusForStep;
        int margin = this.currentStep.getTaskMargin();
        if (margin <= 0) {
            return this.readerLocks;
        }

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = kernel.index(x, z);
                if (this.writerLocks[idx] != null) {
                    this.addReaderMargin(x, z, margin);
                }
            }
        }

        return this.readerLocks;
    }

    private void addReaderMargin(
            int centerX, int centerZ, int margin
    ) {
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

                if (this.writerLocks[idx] == null && this.readerLocks[idx] == null) {
                    ChunkEntry entry = chunks.expectEntry(x + pos.x, z + pos.z);
                    this.readerLocks[idx] = entry.getState();
                }
            }
        }
    }

    @Nullable
    @Override
    public AcquiredChunks poll(Waker waker) {
        if (!this.pollWriters(waker)) {
            return null;
        }

        if (this.needsReaders() && !this.pollReaders(waker)) {
            return null;
        }

        return this.acquiredChunks;
    }

    private boolean pollWriters(Waker waker) {
        if (this.writerGuard == null) {
            RwLock<ChunkEntryState>[] locks = this.collectWriterLocks();
            this.writerGuard = this.acquireWrite.poll(waker, locks, this.acquiredChunks.writerEntries);
        }

        return this.writerGuard != null;
    }

    private boolean pollReaders(Waker waker) {
        if (this.readerGuard == null) {
            RwLock<ChunkEntryState>[] locks = this.collectReaderLocks();
            this.readerGuard = this.acquireRead.poll(waker, locks, this.acquiredChunks.readerEntries);
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

    private boolean needsReaders() {
        return this.currentStep.getTaskMargin() > 0;
    }
}
