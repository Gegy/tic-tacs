package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.loader.ChunkLoader;
import net.gegy1000.acttwo.lock.RwGuard;
import net.gegy1000.acttwo.lock.WriteRwGuard;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.List;

// TODO: can this be cleaned up?
final class UpgradeChunkFuture implements Future<Chunk> {
    private final ChunkLoader chunkLoader;
    private final ChunkUpgrader chunkUpgrader;
    private final ChunkEntry entry;

    private final ChunkUpgrade upgrade;
    private final GetChunkContextFuture upgradeContext;

    private int upgradeIndex;

    private Future<WriteRwGuard<ChunkEntryState>> entryStateFuture;
    private RwGuard<ChunkEntryState> entryState;

    private Future<Chunk> runTask;
    private Future<Chunk> finalizeTask;

    private boolean duplicateLight = true;

    UpgradeChunkFuture(
            ChunkLoader chunkLoader, ChunkUpgrader chunkUpgrader, ChunkEntry entry,
            ChunkUpgrade upgrade, GetChunkContextFuture upgradeContext
    ) {
        this.chunkLoader = chunkLoader;
        this.chunkUpgrader = chunkUpgrader;
        this.entry = entry;
        this.upgrade = upgrade;
        this.upgradeContext = upgradeContext;
    }

    @Nullable
    private ChunkEntryState pollOrAcquireEntryState(Waker waker) {
        if (this.entryState != null) {
            return this.entryState.get();
        }

        if (this.entryStateFuture == null) {
            this.entryStateFuture = this.entry.write();
        }

        RwGuard<ChunkEntryState> poll = this.entryStateFuture.poll(waker);
        if (poll != null) {
            this.entryState = poll;
            this.entryStateFuture = null;
            return this.entryState.get();
        }

        return null;
    }

    private void releaseEntry() {
        if (this.entryState != null) {
            this.entryState.release();
            this.entryState = null;
            this.entryStateFuture = null;
        }
    }

    @Nullable
    @Override
    public Chunk poll(Waker waker) {
        while (true) {
            System.out.println(this.entry.getPos() + ": poll step (" + this.upgradeIndex + ")");

            Chunk poll = this.pollAndCompleteStep(waker, this.upgradeIndex);
            if (poll == null) {
                System.out.println(this.entry.getPos() + ": pending. releasing entry");

                // if we're unable to progress and have a lock on the entry, release it until we can progress again
                this.releaseEntry();

                return null;
            }

            if (this.duplicateLight && this.upgrade.steps[this.upgradeIndex] == ChunkStatus.LIGHT) {
                this.duplicateLight = false;
                continue;
            }

            if (++this.upgradeIndex >= this.upgrade.steps.length) {
                return poll;
            }
        }
    }

    @Nullable
    private Chunk pollAndCompleteStep(Waker waker, int index) {
        ChunkStatus status = this.upgrade.steps[index];

        // TODO: either the load context operation needs to not the current chunk,
        //       or we need to get the chunk and its lock FROM the context
        //      note that currently, we depend on the context containing the current chunk!
        //      we never load it for ourselves at first

        // acquire write access to the chunk entry for upgrading
        ChunkEntryState entry = this.pollOrAcquireEntryState(waker);
        if (entry == null) {
            return null;
        }

        System.out.println(this.entry.getPos() + ": polling for " + status);

        try {
            Chunk poll = this.pollStep(waker, entry, status);
            if (poll == null) {
                return null;
            }

            this.chunkUpgrader.completeUpgradeOk(entry, status, poll);
            this.completeStep(index);

            return poll;
        } catch (ChunkNotLoadedException err) {
            this.chunkUpgrader.completeUpgradeErr(entry, status, err);
            throw err;
        }
    }

    private void completeStep(int index) {
        if (index < this.upgrade.steps.length - 1) {
            ChunkStatus step = this.upgrade.steps[index];
            this.upgradeContext.upgradeTo(ChunkContext.forStatus(step));
        }

        this.runTask = null;
        this.finalizeTask = null;
    }

    private Chunk pollStep(Waker waker, ChunkEntryState entry, ChunkStatus status) {
        Chunk poll;
        if (status == ChunkStatus.EMPTY) {
            poll = this.pollLoadStep(waker);
        } else if (status == ChunkStatus.FULL) {
            poll = this.pollFullStep(waker, entry);
        } else {
            poll = this.pollStepWithContext(waker, entry, status);
        }
        return poll;
    }

    @Nullable
    private Chunk pollStepWithContext(Waker waker, ChunkEntryState entry, ChunkStatus status) {
        System.out.println(this.entry.getPos() + ": polling context for " + status);

        RwGuard<List<Chunk>> context = this.upgradeContext.poll(waker);
        if (context == null) {
            return null;
        }

        System.out.println(this.entry.getPos() + ": polling on upgrade task for " + status);

        if (this.runTask == null) {
            this.runTask = this.chunkUpgrader.runUpgradeTask(entry, status, context.get());
        }

        Chunk poll = this.runTask.poll(waker);
        if (poll != null) {
            context.release();
            return poll;
        }

        return null;
    }

    private Chunk pollFullStep(Waker waker, ChunkEntryState entry) {
        if (this.finalizeTask != null) {
            return this.finalizeTask.poll(waker);
        }

        Chunk poll = this.pollStepWithContext(waker, entry, ChunkStatus.FULL);
        if (poll != null) {
            this.finalizeTask = this.chunkUpgrader.runFinalizeTask(entry, ChunkStatus.FULL, poll);
            return this.finalizeTask.poll(waker);
        }

        return null;
    }

    @Nullable
    private Chunk pollLoadStep(Waker waker) {
        if (this.runTask == null) {
            this.runTask = this.chunkLoader.spawnLoadChunk(this.entry);
        }
        return this.runTask.poll(waker);
    }
}
