package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkMap;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

final class ChunkUpgradeFuture implements Future<Unit> {
    final ChunkController controller;

    final ChunkPos pos;
    final ChunkStep targetStep;

    private final ChunkUpgradeStepper stepper;

    private final ChunkUpgradeEntries entries;
    private volatile AcquireChunks acquireChunks;

    private volatile ChunkStep currentStep;

    private volatile Future<Unit> acquireStep;
    private volatile boolean stepReady;

    private volatile Future<Unit> flushListener;

    public ChunkUpgradeFuture(ChunkController controller, ChunkPos pos, ChunkStep targetStep) {
        this.controller = controller;
        this.pos = pos;
        this.targetStep = targetStep;

        this.stepper = new ChunkUpgradeStepper(this);
        this.entries = new ChunkUpgradeEntries(ChunkUpgradeKernel.forStep(targetStep));
    }

    @Nullable
    @Override
    public Unit poll(Waker waker) {
        if (!this.entries.acquired) {
            if (!this.pollEntries(waker)) {
                return null;
            }

            // if the lowest step in this area is greater than or equal to our target, we have no work to do
            if (this.entries.minimumStep != null && this.entries.minimumStep.greaterOrEqual(this.targetStep)) {
                return Unit.INSTANCE;
            }

            this.currentStep = this.entries.minimumStep != null ? this.entries.minimumStep.getNext() : ChunkStep.EMPTY;
        }

        while (true) {
            ChunkStep currentStep = this.currentStep;

            if (!this.pollStepReady(currentStep, waker)) {
                return null;
            }

            if (this.acquireChunks == null) {
                this.acquireChunks = AcquireChunks.open(this.entries, this.targetStep);
            }

            // poll to acquire read/write access to all the relevant entries
            AcquireChunks.Result result = this.acquireChunks.poll(waker, currentStep);
            if (result == null) {
                return null;
            }

            // if some of the chunk entries have unloaded since we've started, we can't continue
            if (result == AcquireChunks.Result.UNLOADED) {
                this.notifyUpgradeUnloaded(currentStep);
                this.releaseStep();

                return Unit.INSTANCE;
            }

            try {
                if (result == AcquireChunks.Result.OK) {
                    Chunk[] pollChunks = this.stepper.pollStep(waker, this.entries, this.acquireChunks, currentStep);
                    if (pollChunks == null) {
                        return null;
                    }

                    this.notifyUpgrades(pollChunks, currentStep);
                }

                this.releaseStep();
            } catch (ChunkNotLoadedException err) {
                this.notifyUpgradeUnloaded(currentStep);
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
        ChunkUpgradeEntries entries = this.entries;
        ChunkUpgrader upgrader = this.controller.getUpgrader();

        ChunkUpgradeKernel kernel = ChunkUpgradeKernel.forStep(this.targetStep);
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
        for (ChunkEntry entry : this.entries) {
            upgrader.notifyUpgradeUnloaded(entry, step);
        }
    }

    private boolean pollEntries(Waker waker) {
        if (this.flushListener != null) {
            if (this.flushListener.poll(waker) == null) {
                return false;
            } else {
                this.flushListener = null;
            }
        }

        while (true) {
            ChunkAccess chunks = this.controller.getMap().visible();

            // acquire a flush listener now so that we can be sure nothing has changed since we checked the entries
            ChunkMap.FlushListener flushListener = this.controller.getMap().awaitFlush();

            ChunkUpgradeKernel kernel = ChunkUpgradeKernel.forStep(this.targetStep);

            boolean acquired = this.entries.tryAcquire(chunks, this.pos, kernel);

            // not all of the required entries are loaded: wait for the entry list to update
            if (!acquired) {
                // if a flush has happened since we last checked, try again now
                if (flushListener.poll(waker) != null) {
                    continue;
                }

                this.flushListener = flushListener;
                return false;
            }

            // we have everything we need: we don't need to listen for flushes anymore
            flushListener.invalidateWaker();

            return true;
        }
    }

    @Override
    public String toString() {
        StringBuilder display = new StringBuilder();
        display.append("upgrading ").append(this.pos).append(" to ").append(this.targetStep).append(": ");

        if (this.entries.acquired) {
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
            display.append("waiting for entries");
        }

        return display.toString();
    }
}
