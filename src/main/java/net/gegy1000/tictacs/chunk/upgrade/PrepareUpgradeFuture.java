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
import net.minecraft.world.chunk.ProtoChunk;

import org.jetbrains.annotations.Nullable;
import java.util.Arrays;

class PrepareUpgradeFuture implements Future<Result<ChunkUpgrade>> {
    final ChunkController controller;

    final ChunkEntry entry;
    final ChunkStep targetStep;

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

        // we don't want to do redundant work for generating neighbors if this chunk is already loaded
        // the only exception for this is where the chunk step has an on-load task that need to run with context
        this.fromStep = ChunkStep.min(this.targetStep, ChunkStep.MIN_WITH_LOAD_TASK.getPrevious());
    }

    @Nullable
    @Override
    public Result<ChunkUpgrade> poll(Waker waker) {
        // this chunk is no longer valid to be upgraded to our target
        if (!this.entry.isValidAs(this.targetStep)) {
            return Result.error();
        }

        // we iterate downwards until we find a step that we can safely upgrade from
        while (true) {
            // we first need to collect all the required chunk entries
            if (!this.collectedEntries) {
                if (this.entries == null) {
                    this.entries = new ChunkUpgradeEntries(ChunkUpgradeKernel.betweenSteps(this.fromStep, this.targetStep));
                }

                if (!this.pollCollectEntries(waker, this.entries)) {
                    return null;
                }

                this.collectedEntries = true;
            }

            // we then need to load the relevant chunks from disk to test if this upgrade is valid
            Chunk[] chunks = this.pollLoadChunks(waker, this.entries);
            if (chunks == null) {
                return null;
            }

            ChunkStep newStep = this.tryStep(chunks);
            if (newStep == null) {
                // we've loaded enough context
                this.notifyChunkLoads(chunks);
                return Result.ok(new ChunkUpgrade(this.fromStep, this.targetStep, this.entries));
            }

            this.fromStep = newStep;

            this.entries = null;
            this.loadFutures = null;
            this.loadedChunks = null;
            this.collectedEntries = false;
        }
    }

    private void notifyChunkLoads(Chunk[] chunks) {
        ChunkUpgradeKernel kernel = this.entries.kernel;
        ChunkUpgrader upgrader = this.controller.getUpgrader();

        int radius = kernel.getRadius();
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkEntry entry = this.entries.getEntry(x, z);
                if (entry.canUpgradeTo(this.fromStep)) {
                    Chunk chunk = chunks[kernel.index(x, z)];
                    upgrader.notifyUpgradeOk(entry, this.fromStep, chunk);
                }
            }
        }
    }

    @Nullable
    private ChunkStep tryStep(Chunk[] chunks) {
        // we have reached the lowest step
        if (this.fromStep == ChunkStep.EMPTY) {
            return null;
        }

        ChunkStep minimumStep = this.getMinimumStepFor(chunks);

        // the lowest step in this area is greater than or equal to the step we are trying to upgrade from
        if (this.fromStep.lessOrEqual(minimumStep)) {
            return null;
        }

        // we try upgrade from the lowest step in the area
        return minimumStep;
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
        ChunkUpgradeKernel kernel = entries.kernel;
        int radius = kernel.getRadius();

        if (this.loadFutures == null) {
            this.loadFutures = kernel.create(Future[]::new);
            this.loadedChunks = kernel.create(Chunk[]::new);
            ChunkUpgrader upgrader = this.controller.getUpgrader();

            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    ChunkEntry entry = entries.getEntry(x, z);

                    ProtoChunk chunk = entry.getProtoChunk();
                    if (chunk != null) {
                        this.loadedChunks[kernel.index(x, z)] = chunk;
                    } else {
                        this.loadFutures[kernel.index(x, z)] = upgrader.loadChunk(entry);
                    }
                }
            }
        }

        return JoinAllArray.poll(waker, this.loadFutures, this.loadedChunks);
    }
}
