package net.gegy1000.tictacs.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.async.lock.Lock;
import net.gegy1000.tictacs.async.lock.LockGuard;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkEntry extends ChunkHolder {
    public static final int FULL_LEVEL = 33;

    public static final int LIGHT_TICKET_LEVEL = FULL_LEVEL + ChunkStep.getDistanceFromFull(ChunkStep.LIGHTING.getPrevious());

    private static final LevelUpdateListener LEVEL_UPDATE_LISTENER = (pos, get, level, set) -> set.accept(level);

    final ChunkListener[] listeners = new ChunkListener[ChunkStep.STEPS.size()];

    private final ChunkEntryState state = new ChunkEntryState(this);
    private final ChunkAccessLock lock = new ChunkAccessLock();

    private final AtomicReference<ChunkStep> spawnedStep = new AtomicReference<>();

    public ChunkEntry(
            ChunkPos pos, int level,
            LightingProvider lighting,
            PlayersWatchingChunkProvider watchers
    ) {
        super(pos, level, lighting, LEVEL_UPDATE_LISTENER, watchers);

        for (int i = 0; i < this.listeners.length; i++) {
            this.listeners[i] = new ChunkListener();
        }
    }

    public Future<LockGuard<ChunkEntryState>> lock() {
        Lock lock = this.lock.lockAll();
        return lock.acquireAsync().map(u -> new LockGuard<>(lock, this.state));
    }

    public ChunkAccessLock getLock() {
        return this.lock;
    }

    public ChunkEntryState getState() {
        return this.state;
    }

    public ChunkListener getListenerFor(ChunkStep step) {
        return this.listeners[step.getIndex()];
    }

    @Nullable
    public ChunkStep getCurrentStep() {
        return this.state.getCurrentStep();
    }

    public boolean trySpawnUpgradeTo(ChunkStep toStep) {
        while (true) {
            ChunkStep fromStep = this.spawnedStep.get();
            if (fromStep != null && fromStep.greaterOrEqual(toStep)) {
                return false;
            }

            if (this.spawnedStep.compareAndSet(fromStep, toStep)) {
                return true;
            }
        }
    }

    public boolean canUpgradeTo(ChunkStep toStep) {
        if (!this.getTargetStep().greaterOrEqual(toStep)) {
            return false;
        }

        ChunkStep currentStep = this.state.getCurrentStep();
        return currentStep == null || !currentStep.greaterOrEqual(toStep);
    }

    public ChunkStep getTargetStep() {
        return getTargetStep(this.getLevel());
    }

    public static ChunkStep getTargetStep(int level) {
        int distanceFromFull = level - FULL_LEVEL;
        return ChunkStep.byDistanceFromFull(distanceFromFull);
    }

    public void onUpdateLevel(ThreadedAnvilChunkStorage tacs) {
        if (this.level > this.lastTickLevel) {
            this.reduceLevel(this.lastTickLevel, this.level);
        }

        super.tick(tacs);

        this.completedLevel = this.level;
    }

    private void reduceLevel(int lastLevel, int level) {
        boolean wasLoaded = ChunkLevelTracker.isLoaded(lastLevel);
        if (!wasLoaded) {
            return;
        }

        boolean isLoaded = ChunkLevelTracker.isLoaded(level);

        ChunkStep lastStep = getTargetStep(lastLevel);
        ChunkStep targetStep = getTargetStep(level);

        int startIdx = isLoaded ? targetStep.getIndex() + 1 : 0;
        int endIdx = lastStep.getIndex();

        if (startIdx > endIdx) {
            return;
        }

        ChunkNotLoadedException unloaded = new ChunkNotLoadedException(this.pos);
        for (int i = startIdx; i <= endIdx; i++) {
            this.listeners[i].completeErr(unloaded);
        }
    }

    @Nullable
    @Override
    public WorldChunk getWorldChunk() {
        // safety: once upgraded to world chunk, the lock is supposed to have no write access
        return this.getState().getWorldChunk();
    }

    @Override
    @Deprecated
    public CompletableFuture<Either<Chunk, Unloaded>> createFuture(ChunkStatus status, ThreadedAnvilChunkStorage tacs) {
        return tacs.createChunkFuture(this, status);
    }

    @Override
    @Deprecated
    public CompletableFuture<Either<Chunk, Unloaded>> getFuture(ChunkStatus status) {
        ChunkStep step = ChunkStep.byStatus(status);
        return this.getListenerFor(step).vanilla;
    }

    @Override
    @Deprecated
    public CompletableFuture<Either<Chunk, Unloaded>> getNowFuture(ChunkStatus status) {
        ChunkStep step = ChunkStep.byStatus(status);
        return this.getTargetStep().greaterOrEqual(step) ? this.getFuture(status) : ChunkHolder.UNLOADED_CHUNK_FUTURE;
    }

    @Override
    @Deprecated
    public CompletableFuture<Chunk> getFuture() {
        ChunkStep currentStep = this.state.getCurrentStep();
        if (currentStep == null) {
            return CompletableFuture.completedFuture(null);
        }

        return this.getListenerFor(currentStep).vanilla
                .thenApply(result -> {
                    return result.map(chunk -> chunk, err -> null);
                });
    }

    @Override
    @Deprecated
    protected void tick(ThreadedAnvilChunkStorage tacs) {
        this.onUpdateLevel(tacs);
    }

    @Override
    @Nullable
    @Deprecated
    public Chunk getCompletedChunk() {
        return this.state.getChunk();
    }

    @Override
    @Deprecated
    public void method_20456(ReadOnlyChunk chunk) {
    }
}
