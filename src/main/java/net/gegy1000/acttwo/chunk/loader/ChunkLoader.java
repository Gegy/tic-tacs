package net.gegy1000.acttwo.chunk.loader;

import net.gegy1000.acttwo.chunk.ChunkAccess;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.FutureHandle;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.future.AwaitAll;
import net.gegy1000.acttwo.chunk.step.ChunkStep;
import net.gegy1000.acttwo.chunk.tracker.ChunkQueues;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.function.BooleanSupplier;

public final class ChunkLoader {
    private final ServerWorld world;

    private final ChunkController controller;

    private final WorldGenerationProgressListener progressListener;

    public ChunkLoader(ServerWorld world, ChunkController controller, WorldGenerationProgressListener progressListener) {
        this.world = world;
        this.controller = controller;
        this.progressListener = progressListener;
    }

    public Future<Unit> loadRadius(ChunkPos pos, int radius, ChunkStep step) {
        ChunkAccess chunks = this.controller.map.visible();

        ChunkMap.FlushListener flushListener = this.controller.map.awaitFlush();

        int size = radius * 2 + 1;
        Future<ChunkEntry>[] futures = new Future[size * size];
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * size;
                ChunkEntry entry = chunks.getEntry(pos.x + x, pos.z + z);
                if (entry == null) {
                    return flushListener.andThen(unit -> this.loadRadius(pos, radius, step));
                }

                this.controller.upgrader.spawnUpgradeTo(entry, step);
                futures[idx] = entry.getListenerFor(step);
            }
        }

        flushListener.invalidate();

        return new AwaitAll<>(futures);
    }

    public Future<Chunk> spawnLoadChunk(ChunkEntry entry) {
        FutureHandle<Chunk> handle = new FutureHandle<>();

        this.controller.spawnOnMainThread(entry, Future.lazy(() -> {
            Chunk chunk = this.controller.storage.loadChunk(entry.getPos());
            handle.complete(chunk);
            return chunk;
        }));

        return handle;
    }

    public void tick(BooleanSupplier runWhile) {
        ChunkMap access = this.controller.map;
        ChunkQueues queues = access.getQueues();

        access.flushToVisible();

        if (!this.world.isSavingDisabled()) {
            if (!runWhile.getAsBoolean()) return;

            Profiler profiler = this.world.getProfiler();

            profiler.push("chunk_unload");
            queues.acceptUnloadQueue(this::spawnUnloadChunk);
            profiler.pop();
        }
    }

    private void spawnUnloadChunk(long pos) {
        ChunkEntry entry = this.controller.map.primary().removeEntry(pos);
        if (entry != null) {
            this.controller.spawnOnMainThread(entry, this.unloadChunk(entry));
        }
    }

    private Future<Unit> unloadChunk(ChunkEntry entry) {
        return entry.write().map(write -> {
            try {
                ChunkEntryState entryState = write.get();

                Chunk chunk = entryState.getChunk();
                if (chunk == null) {
                    return Unit.INSTANCE;
                }

                ChunkPos pos = entryState.getPos();

                if (chunk instanceof WorldChunk) {
                    ((WorldChunk) chunk).setLoadedToWorld(false);
                    if (this.controller.map.tryRemoveFullChunk(pos)) {
                        this.world.unloadEntities((WorldChunk) chunk);
                    }
                }

                this.controller.storage.saveChunk(chunk);

                this.notifyUnload(pos);
            } finally {
                write.release();
            }

            return Unit.INSTANCE;
        });
    }

    private void notifyUnload(ChunkPos pos) {
        ServerLightingProvider lighting = this.world.getChunkManager().getLightingProvider();
        lighting.updateChunkStatus(pos);
        lighting.tick();

        this.notifyStatus(pos, null);
    }

    public void notifyStatus(ChunkPos pos, ChunkStatus status) {
        this.progressListener.setChunkStatus(pos, status);
    }
}
