package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.lock.RwGuard;
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
            // to start off: find the minimum status within our kernel. from here we'll upgrade iteratively
            ChunkStatus minimumStatus = this.findMinimumStatus();

            // if the minimum status is already our target status, there is no work to be done
            if (minimumStatus != null && minimumStatus.isAtLeast(this.targetStatus)) {
                return Unit.INSTANCE;
            }

            this.upgrade = new ChunkUpgrade(minimumStatus, this.targetStatus);
//			System.out.println(this.pos + ": running upgrade with steps: " + Arrays.toString(this.upgrade.steps));
        }

        while (true) {
            ChunkStatus currentStatus = this.upgrade.steps[this.stepIdx];
            ChunkEntryKernel entries = this.entryKernel;

            if (entries.isIdle()) {
                // TODO: problem: we must only actually lock everything once we know that *everything* is free
                //       otherwise, when contended, we end up in a dead-locked state.
                //          one way, although horrible, is to poll acquiring the locks by adding to the list
                //          until we can't acquire anymore, and at that point we release everything that's existing
                //          somehow though, we still have to be notified when the locks are unlocked, so this might not
                //          be so useful.

                // collect all the chunk entries within the kernel that still need to be upgraded to currentStatus
                int writeCount = entries.prepareForUpgrade(this.controller.access.getMap(), this.pos, currentStatus);

                if (writeCount <= 0) {
                    // if we couldn't collect any entries, we must be complete already
                    entries.release();
                    return Unit.INSTANCE;
                }
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

                System.out.println(this.pos + ": notifying successful upgrades to " + currentStatus);
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
        RwGuard<ChunkEntryState>[] entries = entryKernel.getEntries();

        int radius = this.upgradeKernel.getRadius();
        int size = this.upgradeKernel.getSize();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * size;

                RwGuard<ChunkEntryState> entry = entries[idx];
                Chunk chunk = chunks[idx];
                if (entry == null || chunk == null) {
                    continue;
                }

                this.controller.upgrader.completeUpgradeOk(entry.get(), status, chunk);
            }
        }
    }

    private void notifyChunkUpgradeError(ChunkEntryKernel entryKernel, ChunkStatus status, ChunkNotLoadedException err) {
        RwGuard<ChunkEntryState>[] entries = entryKernel.getEntries();

        int radius = this.upgradeKernel.getRadius();
        int size = this.upgradeKernel.getSize();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * size;

                RwGuard<ChunkEntryState> entry = entries[idx];
                if (entry == null) {
                    continue;
                }

                this.controller.upgrader.completeUpgradeErr(entry.get(), status, err);
            }
        }
    }

    @Nullable
    private ChunkStatus findMinimumStatus() {
        int centerX = this.pos.x;
        int centerZ = this.pos.z;

        ChunkStatus minimumStatus = ChunkStatus.FULL;

        ChunkMap chunks = this.controller.access.getMap();

        int radius = this.upgradeKernel.getRadius();
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkEntry entry = chunks.getEntry(x + centerX, z + centerZ);
                if (entry == null) {
                    return null;
                }

                ChunkStatus status = entry.getCurrentStatus();
                if (status == null) {
                    return null;
                }

                if (!minimumStatus.isAtLeast(status)) {
                    minimumStatus = status;
                }
            }
        }

        return minimumStatus;
    }
}
