package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkMap;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.future.JoinAllArray;
import net.gegy1000.tictacs.chunk.future.Result;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;

import javax.annotation.Nullable;
import java.util.Arrays;

class PrepareUpgradeFuture implements Future<Result<ChunkUpgrade>> {
    static final Result<ChunkUpgrade> EMPTY_UPGRADE = Result.ok(ChunkUpgrade.EMPTY);

    final ChunkController controller;

    final ChunkEntry entry;
    final ChunkStep targetStep;

    private volatile Future<Chunk> loadFuture;
    private volatile ChunkStep fromStep;

    private volatile ChunkUpgradeEntries entries;
    private volatile Future<Chunk>[] loadFutures;
    private volatile Chunk[] loadedChunks;

    private volatile boolean collectedEntries;

    private volatile Future<Unit> flushListener;

    PrepareUpgradeFuture(ChunkController controller, ChunkEntry entry, ChunkStep targetStep) {
        this.controller = controller;
        this.entry = entry;
        this.targetStep = targetStep;
    }

    @Nullable
    @Override
    public Result<ChunkUpgrade> poll(Waker waker) {
        if (this.fromStep == null) {
            // we need to load the current chunk to find out from what point we should be upgrading from
            // if the chunk is already generated to a certain point, we have less work to do

            Result<Chunk> result = this.pollLoadChunk(waker);
            if (result == null) {
                return null;
            } else if (result.isError()) {
                return Result.error();
            }

            Chunk chunk = result.get();
            ChunkStatus status = chunk.getStatus();
            ChunkStep fromStep = ChunkStep.byFullStatus(status);

            // we don't want to do redundant work for generating neighbors if this chunk is already loaded
            // the only exception for this is where the chunk step has an on-load task that need to run with context
            fromStep = ChunkStep.min(fromStep, ChunkStep.MIN_WITH_LOAD_TASK.getPrevious());

            this.controller.getUpgrader().notifyUpgradeOk(this.entry, fromStep, chunk);

            // our chunk is ready as our target step, we have no work to do
            if (this.targetStep.lessOrEqual(fromStep)) {
                return EMPTY_UPGRADE;
            }

            this.fromStep = fromStep;
        }

        // we iterate downwards until we find a step that we can safely upgrade from
        while (this.fromStep != ChunkStep.EMPTY) {
            if (this.entries == null) {
                this.entries = new ChunkUpgradeEntries(ChunkUpgradeKernel.betweenSteps(this.fromStep, this.targetStep));
            }

            // we first need to collect all the required chunk entries
            if (!this.collectedEntries) {
                if (this.pollCollectEntries(waker, this.entries)) {
                    this.collectedEntries = true;
                } else {
                    return null;
                }
            }

            // we then need to load the relevant chunks from disk to test if this upgrade is valid
            Chunk[] chunks = this.pollLoadChunks(waker, this.entries);
            if (chunks == null) {
                return null;
            }

            ChunkStep minimumStep = this.getMinimumStepFor(chunks);

            // the lowest step in this area is greater than or equal to the step we are trying to upgrade from
            if (this.fromStep.lessOrEqual(minimumStep)) {
                break;
            }

            this.fromStep = minimumStep;

            this.entries = null;
            this.loadFutures = null;
            this.loadedChunks = null;
            this.collectedEntries = false;
        }

        return Result.ok(new ChunkUpgrade(this.fromStep, this.targetStep, this.entries));
    }

    @Nullable
    private ChunkStep getMinimumStepFor(Chunk[] chunks) {
        ChunkStep minimumStep = ChunkStep.FULL;

        for (Chunk chunk : chunks) {
            ChunkStep step = ChunkStep.byFullStatus(chunk.getStatus());
            if (step == ChunkStep.EMPTY) {
                return ChunkStep.EMPTY;
            }

            if (step.lessThan(minimumStep)) {
                minimumStep = step;
            }
        }

        return minimumStep;
    }

    @Nullable
    private Result<Chunk> pollLoadChunk(Waker waker) {
        // this chunk is no longer valid to be upgraded to our target
        if (!this.entry.isValidAs(this.targetStep)) {
            return Result.error();
        }

        Chunk chunk = this.entry.getChunk();
        if (chunk != null) {
            // the chunk is already loaded, return it
            return Result.ok(chunk);
        }

        // try load the chunk from disk
        if (this.loadFuture == null) {
            this.loadFuture = this.controller.spawnLoadChunk(this.entry);
        }

        Chunk poll = this.loadFuture.poll(waker);
        if (poll != null) {
            this.loadFuture = null;
            return Result.ok(poll);
        }

        return null;
    }

    private boolean pollCollectEntries(Waker waker, ChunkUpgradeEntries entries) {
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

            // not all of the required entries are loaded: wait for the entry list to update
            if (!this.tryCollectEntries(chunks, entries)) {
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

    private boolean tryCollectEntries(ChunkAccess chunks, ChunkUpgradeEntries entries) {
        int originX = this.entry.pos.x;
        int originZ = this.entry.pos.z;

        ChunkEntry[] array = this.entries.entries;
        ChunkUpgradeKernel kernel = entries.kernel;
        int radius = kernel.getRadius();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkEntry entry = chunks.getEntry(x + originX, z + originZ);

                // all chunk entries must be available before upgrading
                if (entry == null) {
                    Arrays.fill(array, null);
                    return false;
                }

                array[kernel.index(x, z)] = entry;
            }
        }

        return true;
    }

    @Nullable
    private Chunk[] pollLoadChunks(Waker waker, ChunkUpgradeEntries entries) {
        if (this.loadFutures == null) {
            ChunkUpgradeKernel kernel = entries.kernel;
            this.loadFutures = kernel.create(Future[]::new);
            this.loadedChunks = kernel.create(Chunk[]::new);

            int radius = kernel.getRadius();

            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    ChunkEntry entry = entries.getEntry(x, z);

                    ProtoChunk chunk = entry.getChunk();
                    if (chunk != null) {
                        this.loadedChunks[kernel.index(x, z)] = chunk;
                    } else {
                        this.loadFutures[kernel.index(x, z)] = this.controller.spawnLoadChunk(entry);
                    }
                }
            }
        }

        return JoinAllArray.poll(waker, this.loadFutures, this.loadedChunks);
    }
}
