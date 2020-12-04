package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.TicTacs;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.future.Result;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.world.chunk.Chunk;

import org.jetbrains.annotations.Nullable;

public final class ChunkUpgradeFuture implements Future<Unit> {
    final ChunkController controller;

    final ChunkEntry entry;
    final ChunkStep targetStep;

    private final ChunkUpgradeStepper stepper;

    private volatile PrepareUpgradeFuture prepareUpgrade;

    private volatile ChunkUpgrade upgrade;
    private volatile AcquireChunks acquireChunks;

    private volatile ChunkStep currentStep;

    private volatile Future<Unit> acquireStep;
    private volatile boolean stepReady;

    public ChunkUpgradeFuture(ChunkController controller, ChunkEntry entry, ChunkStep targetStep) {
        this.controller = controller;
        this.entry = entry;
        this.targetStep = targetStep;

        this.stepper = new ChunkUpgradeStepper(this);
        this.prepareUpgrade = new PrepareUpgradeFuture(controller, entry, targetStep);
    }

    @Nullable
    @Override
    public Unit poll(Waker waker) {
        if (this.upgrade == null) {
            Result<ChunkUpgrade> prepare = this.prepareUpgrade.poll(waker);
            if (prepare == null) {
                return null;
            }

            this.prepareUpgrade = null;

            if (prepare.isError()) {
                // this chunk is no longer valid to be upgraded: abort
                this.controller.getUpgrader().notifyUpgradeUnloaded(this.entry, this.targetStep);
                return Unit.INSTANCE;
            }

            this.upgrade = prepare.get();

            if (this.upgrade.isEmpty()) {
                return Unit.INSTANCE;
            }

            this.currentStep = this.upgrade.fromStep.getNext();
        }

        while (true) {
            ChunkStep currentStep = this.currentStep;

            if (!this.pollStepReady(currentStep, waker)) {
                return null;
            }

            if (this.acquireChunks == null) {
                this.acquireChunks = new AcquireChunks(this.upgrade);
            }

            // poll to acquire read/write access to all the relevant entries
            AcquireChunks.Result result = this.acquireChunks.poll(waker, currentStep);
            if (result == null) {
                return null;
            }

            // if some of the chunk entries have unloaded since we've started, we can't continue
            if (result == AcquireChunks.Result.UNLOADED) {
                return this.returnUnloaded(currentStep);
            }

            try {
                if (result == AcquireChunks.Result.OK) {
                    Chunk[] pollChunks = this.stepper.pollStep(waker, this.upgrade.entries, this.acquireChunks, currentStep);
                    if (pollChunks == null) {
                        return null;
                    }

                    this.notifyUpgrades(pollChunks, currentStep);
                }

                this.releaseStep();
            } catch (ChunkNotLoadedException err) {
                return this.returnUnloaded(currentStep);
            } catch (Exception e) {
                TicTacs.LOGGER.error("Failed to generate chunk at {}", this.entry.getPos(), e);
                return this.returnUnloaded(currentStep);
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

        if (this.acquireStep == null) {
            ChunkStep.Acquire acquire = currentStep.getAcquireTask();
            if (acquire == null) {
                this.stepReady = true;
                return true;
            }

            this.acquireStep = acquire.acquire(this.controller);
        }

        if (this.acquireStep.poll(waker) != null) {
            this.stepReady = true;
            this.acquireStep = null;
            return true;
        }

        return false;
    }

    private Unit returnUnloaded(ChunkStep currentStep) {
        this.notifyUpgradeUnloaded(currentStep);
        this.releaseStep();

        return Unit.INSTANCE;
    }

    private void releaseStep() {
        this.stepper.reset();
        this.acquireChunks.release();

        ChunkStep.Release releaseTask = this.currentStep.getReleaseTask();
        if (releaseTask != null) {
            releaseTask.release(this.controller);
        }

        this.acquireChunks = null;
        this.acquireStep = null;
        this.stepReady = false;
    }

    private void notifyUpgrades(Chunk[] chunks, ChunkStep step) {
        ChunkUpgradeEntries entries = this.upgrade.entries;
        ChunkUpgrader upgrader = this.controller.getUpgrader();

        ChunkUpgradeKernel kernel = this.upgrade.getKernel();
        int radius = kernel.getRadiusFor(step);

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                Chunk chunk = chunks[kernel.index(x, z)];
                if (chunk != null) {
                    ChunkEntry entry = entries.getEntry(x, z);
                    upgrader.notifyUpgradeOk(entry, step, chunk);
                }
            }
        }
    }

    private void notifyUpgradeUnloaded(ChunkStep step) {
        ChunkUpgrader upgrader = this.controller.getUpgrader();

        upgrader.notifyUpgradeUnloaded(this.entry, step);

        // let the chunk entries know that we're not trying to upgrade them anymore
        for (ChunkEntry entry : this.upgrade.entries) {
            entry.notifyUpgradeCanceled(step);
        }
    }

    @Override
    public String toString() {
        StringBuilder display = new StringBuilder();
        display.append("upgrading ").append(this.entry.getPos()).append(" to ").append(this.targetStep).append(": ");

        if (this.upgrade != null) {
            if (this.currentStep.greaterOrEqual(this.targetStep) && this.stepReady && this.acquireChunks.acquired != null) {
                display.append("ready!");
            } else {
                if (this.stepReady) {
                    if (this.acquireChunks.acquired != null) {
                        display.append("waiting for upgrade to ").append(this.currentStep);
                    } else {
                        display.append("waiting to acquire entry locks @").append(this.currentStep);
                    }
                } else {
                    display.append("waiting for ").append(this.currentStep).append(" to be ready");
                }
            }
        } else {
            display.append("preparing upgrade");
        }

        return display.toString();
    }
}
