package net.gegy1000.acttwo.chunk.loader;

import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkAccess;
import net.gegy1000.acttwo.chunk.FutureHandle;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.future.AwaitAll;
import net.gegy1000.acttwo.chunk.tracker.ChunkQueues;
import net.gegy1000.acttwo.lock.RwGuard;
import net.gegy1000.acttwo.lock.WriteRwGuard;
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

import javax.annotation.Nullable;
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

    // TODO: can we have these not be nullable?

    public Future<Unit> loadRadius(ChunkPos pos, int radius, ChunkStatus status) {
        int size = radius * 2 + 1;

        Future<ChunkEntry>[] futures = new Future[size * size];
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * size;
                futures[idx] = this.getChunkEntryAs(pos.x + x, pos.z + z, status);
            }
        }

        return new AwaitAll<>(futures);
    }

    @Nullable
    public Future<ChunkEntry> getChunkEntryAs(ChunkPos pos, ChunkStatus status) {
        return this.getChunkEntryAs(pos.x, pos.z, status);
    }

    @Nullable
    public Future<RwGuard<Chunk>> readChunkAs(ChunkPos pos, ChunkStatus status) {
        return this.readChunkAs(pos.x, pos.z, status);
    }

    @Nullable
    public Future<RwGuard<Chunk>> writeChunkAs(ChunkPos pos, ChunkStatus status) {
        return this.writeChunkAs(pos.x, pos.z, status);
    }

    @Nullable
    public Future<ChunkEntry> getChunkEntryAs(int chunkX, int chunkZ, ChunkStatus status) {
        ChunkEntry entry = this.controller.access.getMap().getEntry(chunkX, chunkZ);
        if (entry == null) return null;

        this.controller.upgrader.spawnUpgradeTo(entry, status);

        return entry.getListenerFor(status);
    }

    @Nullable
    public Future<RwGuard<Chunk>> readChunkAs(int chunkX, int chunkZ, ChunkStatus status) {
        Future<ChunkEntry> entry = this.getChunkEntryAs(chunkX, chunkZ, status);
        if (entry == null) return null;

        Future<RwGuard<ChunkEntryState>> readEntry = entry.andThen(ChunkEntry::read);
        return readEntry.map(guard -> guard.map(ChunkEntryState::getChunk));
    }

    @Nullable
    public Future<RwGuard<Chunk>> writeChunkAs(int chunkX, int chunkZ, ChunkStatus status) {
        Future<ChunkEntry> entry = this.getChunkEntryAs(chunkX, chunkZ, status);
        if (entry == null) return null;

        Future<WriteRwGuard<ChunkEntryState>> writeEntry = entry.andThen(ChunkEntry::write);
        return writeEntry.map(guard -> guard.map(ChunkEntryState::getChunk));
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
        ChunkAccess access = this.controller.access;
        ChunkQueues queues = access.getQueues();

        access.copyIntoReadOnlyMap();

        if (!this.world.isSavingDisabled()) {
            if (!runWhile.getAsBoolean()) return;

            Profiler profiler = this.world.getProfiler();

            profiler.push("chunk_unload");
            queues.acceptUnloadQueue(this::spawnUnloadChunk);
            profiler.pop();
        }
    }

    private void spawnUnloadChunk(long pos) {
        ChunkEntry entry = this.controller.access.getWriteableMap().removeEntry(pos);
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
                    if (this.controller.access.tryRemoveFullChunk(pos)) {
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
