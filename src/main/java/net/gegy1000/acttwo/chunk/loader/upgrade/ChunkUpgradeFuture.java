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

public final class ChunkUpgradeFuture implements Future<Unit> {
    final ChunkController controller;

    final ChunkPos pos;
    final ChunkStatus targetStatus;
    final ChunkUpgradeKernel upgradeKernel;

    private final ChunkEntryKernel entryKernel;
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
        this.entryKernel = new ChunkEntryKernel(this.upgradeKernel);
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
            ChunkEntryKernel entries = this.entryKernel;

            if (entries.isIdle()) {
                // collect all the chunk entries within the kernel that still need to be upgraded to currentStatus
                ChunkAccess chunks = this.controller.map.visible();

                if (!entries.prepareWriters(chunks, this.pos, currentStatus)) {
                    // we don't need to acquire any writers: we must be finished
                    return Unit.INSTANCE;
                }

                entries.prepareReaders(chunks, this.pos, currentStatus);
            }

            // wait to acquire access to all the required entries
            if (entries.pollEntries(waker) == null) {
                return null;
            }

            try {
                Chunk[] pollChunks = this.stepper.pollStep(waker, entries, currentStatus);
                if (pollChunks == null) {
                    return null;
                }

                this.notifyChunkUpgrades(pollChunks, entries, currentStatus);
            } catch (ChunkNotLoadedException err) {
                this.notifyChunkUpgradeError(entries, currentStatus, err);
                throw err;
            }

            // we're done with the current step: release all locks and allocations
            this.stepper.reset();
            entries.release();

            if (++this.stepIdx >= this.upgrade.steps.length) {
                // we've finished upgrading this chunk!
                return Unit.INSTANCE;
            }
        }
    }

    private void notifyChunkUpgrades(Chunk[] chunks, ChunkEntryKernel entryKernel, ChunkStatus status) {
        ChunkEntryState[] entries = entryKernel.getEntries();

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

    private void notifyChunkUpgradeError(ChunkEntryKernel entryKernel, ChunkStatus status, ChunkNotLoadedException err) {
        ChunkEntryState[] entries = entryKernel.getEntries();

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
                if (status == null || !minimumStatus.isAtLeast(status)) {
                    minimumStatus = status;
                }
            }
        }

        return new ChunkUpgrade(minimumStatus, targetStatus);
    }
}
