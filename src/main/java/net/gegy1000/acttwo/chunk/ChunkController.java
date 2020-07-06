package net.gegy1000.acttwo.chunk;

import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.future.LazyRunnableFuture;
import net.gegy1000.acttwo.chunk.loader.ChunkLoader;
import net.gegy1000.acttwo.chunk.loader.ChunkStorage;
import net.gegy1000.acttwo.chunk.loader.upgrade.ChunkUpgrader;
import net.gegy1000.acttwo.chunk.tracker.ChunkTracker;
import net.gegy1000.acttwo.chunk.worker.ChunkMainThreadExecutor;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

public final class ChunkController implements AutoCloseable {
    public final ServerWorld world;

    public final ChunkAccess access;

    public final ChunkStorage storage;
    public final ChunkUpgrader upgrader;
    public final ChunkTracker tracker;
    public final ChunkLoader loader;

    private final ChunkMainThreadExecutor mainExecutor;

    public ChunkController(
            ServerWorld world,
            ChunkGenerator generator,
            StructureManager structures,
            ServerLightingProvider lighting,
            LevelStorage.Session storageSession,
            WorldGenerationProgressListener progressListener,
            Executor threadPool, ThreadExecutor<Runnable> mainThread
    ) {
        this.world = world;

        this.access = new ChunkAccess(world, this);
        this.storage = ChunkStorage.open(world, storageSession);
        this.tracker = new ChunkTracker(world, this.access, threadPool, mainThread);
        this.upgrader = new ChunkUpgrader(world, this, generator, structures, lighting);
        this.loader = new ChunkLoader(world, this, progressListener);

        this.mainExecutor = new ChunkMainThreadExecutor(mainThread);
    }

    public static ChunkController from(ThreadedAnvilChunkStorage tacs) {
        return ((TacsExt) tacs).getController();
    }

    public void tick(BooleanSupplier runWhile) {
        Profiler profiler = this.world.getProfiler();

        profiler.push("storage");
        this.storage.tick(runWhile);

        profiler.swap("loader");
        this.loader.tick(runWhile);

        profiler.pop();
    }

    public <T> void spawnOnMainThread(ChunkEntry entry, Future<T> future) {
        this.mainExecutor.spawn(entry, future);
    }

    public void spawnOnMainThread(ChunkEntry entry, Runnable runnable) {
        this.mainExecutor.spawn(entry, new LazyRunnableFuture(runnable));
    }

    public void saveAll(boolean flush) {
        // TODO
    }

    @Override
    public void close() throws IOException {
        this.mainExecutor.close();
        this.storage.close();
    }
}
