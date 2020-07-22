package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.ChunkAccess;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import javax.annotation.Nullable;

final class ChunkUpgradeFuture implements Future<Unit> {
    final ChunkController controller;

    final ChunkPos pos;
    final ChunkStatus targetStatus;
    final ChunkUpgradeKernel upgradeKernel;

    private final AcquireChunkEntries acquireEntries;
    private final ChunkUpgradeStepper stepper;

    private Future<Unit> flushListener;

    private ChunkUpgrade upgrade;
    private int stepIdx;

    public ChunkUpgradeFuture(
            ChunkController controller,
            ChunkPos pos,
            ChunkStatus targetStatus
    ) {
        this.controller = controller;
        this.pos = pos;
        this.targetStatus = targetStatus;

        this.upgradeKernel = ChunkUpgradeKernel.byStatus(targetStatus);
        this.acquireEntries = new AcquireChunkEntries(this.upgradeKernel, controller.map);
        this.stepper = new ChunkUpgradeStepper(this);
    }

    @Nullable
    @Override
    public Unit poll(Waker waker) {
        if (this.upgrade == null) {
            ChunkUpgrade upgrade = this.pollUpgrade(waker);
            if (upgrade == null) {
                return null;
            }

            // if the upgrade is determined empty, there is no work to be done
            if (upgrade.isEmpty()) {
                return Unit.INSTANCE;
            }

            this.upgrade = upgrade;
        }

        while (true) {
            ChunkStatus currentStatus = this.upgrade.steps[this.stepIdx];
            AcquireChunkEntries acquireEntries = this.acquireEntries;

            if (!acquireEntries.isReady()) {
                acquireEntries.prepare(this.pos, currentStatus);
            }

            // poll to acquire read/write access to all the relevant entries
            AcquiredChunks chunks = acquireEntries.poll(waker);
            if (chunks == null) {
                return null;
            }

            try {
                Chunk[] pollChunks = this.stepper.pollStep(waker, chunks, currentStatus);
                if (pollChunks == null) {
                    return null;
                }

                this.notifyChunkUpgrades(pollChunks, chunks, currentStatus);
                this.releaseStep();
            } catch (ChunkNotLoadedException err) {
                this.notifyChunkUpgradeError(chunks, currentStatus, err);
                this.releaseStep();

                throw err;
            }

            if (++this.stepIdx >= this.upgrade.steps.length) {
                // we've finished upgrading this chunk!
                return Unit.INSTANCE;
            }
        }
    }

    private void releaseStep() {
        this.stepper.reset();
        this.acquireEntries.release();
    }

    private void notifyChunkUpgrades(Chunk[] chunks, AcquiredChunks acquiredChunks, ChunkStatus status) {
        ChunkEntryState[] entries = acquiredChunks.writerEntries;

        int radius = this.upgradeKernel.getRadius();
        int size = this.upgradeKernel.getSize();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * size;

                ChunkEntryState entry = entries[idx];
                Chunk chunk = chunks[idx];
                if (entry == null || chunk == null) {
                    continue;
                }

                this.controller.upgrader.completeUpgradeOk(entry, status, chunk);
            }
        }
    }

    private void notifyChunkUpgradeError(AcquiredChunks chunks, ChunkStatus status, ChunkNotLoadedException err) {
        ChunkEntryState[] entries = chunks.writerEntries;

        int radius = this.upgradeKernel.getRadius();
        int size = this.upgradeKernel.getSize();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * size;

                ChunkEntryState entry = entries[idx];
                if (entry == null) {
                    continue;
                }

                this.controller.upgrader.completeUpgradeErr(entry, status, err);
            }
        }
    }

    @Nullable
    private ChunkUpgrade pollUpgrade(Waker waker) {
        if (this.flushListener != null) {
            if (this.flushListener.poll(waker) == null) {
                return null;
            } else {
                this.flushListener = null;
            }
        }

        while (true) {
            ChunkAccess chunks = this.controller.map.visible();

            // acquire a flush listener now so that we can be sure nothing has changed since we checked the entries
            ChunkMap.FlushListener flushListener = this.controller.map.awaitFlush();

            ChunkUpgrade upgrade = this.prepareUpgrade(chunks, this.targetStatus);

            // not all of the required entries are loaded: wait for the entry list to update
            if (upgrade == null) {
                // if a flush has happened since we last checked, try again now
                if (flushListener.poll(waker) != null) {
                    continue;
                }

                this.flushListener = flushListener;
                return null;
            }

            // we have everything we need: we don't need to listen for flushes anymore
            flushListener.invalidate();

            return upgrade;
        }
    }

    @Nullable
    private ChunkUpgrade prepareUpgrade(ChunkAccess chunks, ChunkStatus targetStatus) {
        int centerX = this.pos.x;
        int centerZ = this.pos.z;

        ChunkStatus minimumStatus = ChunkStatus.FULL;

        int radius = this.upgradeKernel.getRadius();
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkEntry entry = chunks.getEntry(x + centerX, z + centerZ);

                // all chunk entries must be available before upgrading
                if (entry == null) {
                    return null;
                }

                // we've reached the absolute minimum status: no point comparing
                if (minimumStatus == null) {
                    continue;
                }

                ChunkStatus status = entry.getCurrentStatus();
                if (status == null || !status.isAtLeast(minimumStatus)) {
                    minimumStatus = status;
                }
            }
        }

        return new ChunkUpgrade(minimumStatus, targetStatus);
    }
}
