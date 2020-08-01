package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkMap;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.entry.ChunkEntryState;
import net.gegy1000.tictacs.chunk.future.Poll;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

final class ChunkUpgradeFuture implements Future<Unit> {
    final ChunkController controller;

    final ChunkPos pos;
    final ChunkStep targetStep;

    private final ChunkUpgradeStepper stepper;

    private volatile AcquireChunks acquireEntries;
    private volatile ChunkStep currentStep;

    private volatile Future<Unit> prerequisiteListener;
    private volatile boolean stepReady;

    private volatile Future<Unit> flushListener;

    public ChunkUpgradeFuture(
            ChunkController controller,
            ChunkPos pos,
            ChunkStep targetStep
    ) {
        this.controller = controller;
        this.pos = pos;
        this.targetStep = targetStep;

        this.stepper = new ChunkUpgradeStepper(this);
    }

    @Nullable
    @Override
    public Unit poll(Waker waker) {
        if (this.currentStep == null) {
            Poll<ChunkStep> pollMinimumStep = this.pollMinimumStep(waker);
            if (pollMinimumStep.isPending()) {
                return null;
            }

            ChunkStep minimumStep = pollMinimumStep.get();

            // if the lowest step in this area is greater than or equal to our target, we have no work to do
            if (minimumStep != null && minimumStep.greaterOrEqual(this.targetStep)) {
                return Unit.INSTANCE;
            }

            this.currentStep = minimumStep != null ? minimumStep.getNext() : ChunkStep.EMPTY;
        }

        while (true) {
            ChunkStep currentStep = this.currentStep;

            if (!this.pollStepReady(currentStep, waker)) {
                return null;
            }

            if (this.acquireEntries == null) {
                this.acquireEntries = AcquireChunks.open(this.targetStep);
            }

            // poll to acquire read/write access to all the relevant entries
            ChunkAccess chunkAccess = this.controller.getMap().visible();
            AcquireChunks.Result chunks = this.acquireEntries.poll(waker, chunkAccess, this.pos, currentStep);
            if (chunks == null) {
                return null;
            }

            try {
                if (!chunks.empty) {
                    Chunk[] pollChunks = this.stepper.pollStep(waker, chunks, currentStep);
                    if (pollChunks == null) {
                        return null;
                    }

                    this.notifyChunkUpgrades(pollChunks, chunks, currentStep);
                }

                this.releaseStep();
            } catch (ChunkNotLoadedException err) {
                this.notifyChunkUpgradeError(chunks, currentStep);
                this.releaseStep();

                return Unit.INSTANCE;
            }

            if (currentStep.lessThan(this.targetStep)) {
                this.currentStep = currentStep.getNext();
            } else {
                // we've finished upgrading this chunk!
                return Unit.INSTANCE;
            }
        }
    }

    private boolean pollStepReady(ChunkStep currentStep, Waker waker) {
        if (this.stepReady) {
            return true;
        }

        if (this.prerequisiteListener == null) {
            ChunkStep.Prerequisite prerequisite = currentStep.getPrerequisite();
            if (prerequisite == null) {
                this.stepReady = true;
                return true;
            }

            this.prerequisiteListener = prerequisite.await(this.controller);
        }

        if (this.prerequisiteListener.poll(waker) != null) {
            this.stepReady = true;
            this.prerequisiteListener = null;
            return true;
        }

        return false;
    }

    private void releaseStep() {
        this.stepper.reset();
        this.acquireEntries.release();

        this.acquireEntries = null;
        this.prerequisiteListener = null;
        this.stepReady = false;
    }

    private void notifyChunkUpgrades(Chunk[] chunks, AcquireChunks.Result acquiredChunks, ChunkStep step) {
        ChunkEntryState[] entries = acquiredChunks.entries;

        ChunkUpgradeKernel kernel = acquiredChunks.getKernel();
        int radius = kernel.getRadius();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = kernel.index(x, z);

                ChunkEntryState entry = entries[idx];
                Chunk chunk = chunks[idx];
                if (entry == null || chunk == null) {
                    continue;
                }

                this.controller.getUpgrader().completeUpgradeOk(entry, step, chunk);
            }
        }
    }

    private void notifyChunkUpgradeError(AcquireChunks.Result chunks, ChunkStep step) {
        ChunkEntryState[] entries = chunks.entries;
        ChunkUpgradeKernel kernel = chunks.getKernel();
        int radius = kernel.getRadius();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = kernel.index(x, z);

                ChunkEntryState entry = entries[idx];
                if (entry == null) {
                    continue;
                }

                this.controller.getUpgrader().completeUpgradeErr(entry, step);
            }
        }
    }

    private Poll<ChunkStep> pollMinimumStep(Waker waker) {
        if (this.flushListener != null) {
            if (this.flushListener.poll(waker) == null) {
                return Poll.pending();
            } else {
                this.flushListener = null;
            }
        }

        while (true) {
            ChunkAccess chunks = this.controller.getMap().visible();

            // acquire a flush listener now so that we can be sure nothing has changed since we checked the entries
            ChunkMap.FlushListener flushListener = this.controller.getMap().awaitFlush();

            Poll<ChunkStep> minimumStep = this.findMinimumStep(chunks);

            // not all of the required entries are loaded: wait for the entry list to update
            if (minimumStep.isPending()) {
                // if a flush has happened since we last checked, try again now
                if (flushListener.poll(waker) != null) {
                    continue;
                }

                this.flushListener = flushListener;
                return Poll.pending();
            }

            // we have everything we need: we don't need to listen for flushes anymore
            flushListener.invalidate();

            return minimumStep;
        }
    }

    private Poll<ChunkStep> findMinimumStep(ChunkAccess chunks) {
        int centerX = this.pos.x;
        int centerZ = this.pos.z;

        ChunkStep minimumStep = ChunkStep.FULL;

        int radius = ChunkUpgradeKernel.forStep(this.targetStep).getRadius();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkEntry entry = chunks.getEntry(x + centerX, z + centerZ);

                // all chunk entries must be available before upgrading
                if (entry == null) {
                    return Poll.pending();
                }

                // we've reached the absolute minimum status: no point comparing
                if (minimumStep == null) {
                    continue;
                }

                ChunkStep step = entry.getCurrentStep();
                if (step == null || step.lessThan(minimumStep)) {
                    minimumStep = step;
                }
            }
        }

        return Poll.ready(minimumStep);
    }

    @Override
    public String toString() {
        StringBuilder display = new StringBuilder();
        display.append("upgrading ").append(this.pos).append(" to ").append(this.targetStep).append(": ");

        if (this.currentStep != null) {
            if (this.currentStep.greaterOrEqual(this.targetStep)) {
                display.append("ready!");
            } else {
                if (this.stepReady) {
                    if (this.acquireEntries.acquired) {
                        display.append("waiting for upgrade to ").append(this.currentStep);
                    } else {
                        display.append("waiting to acquire entry locks @").append(this.currentStep);
                    }
                } else {
                    display.append("waiting to for ").append(this.currentStep).append(" to be ready");
                }
            }
        } else {
            display.append("waiting for entries");
        }

        return display.toString();
    }
}
