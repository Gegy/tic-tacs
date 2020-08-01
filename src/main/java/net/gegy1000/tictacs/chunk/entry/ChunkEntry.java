package net.gegy1000.tictacs.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.async.lock.Lock;
import net.gegy1000.tictacs.async.lock.LockGuard;
import net.gegy1000.tictacs.async.worker.ChunkTask;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
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

    final ChunkListener[] listeners = new ChunkListener[ChunkStep.STEPS.size()];

    private final ChunkEntryState state = new ChunkEntryState(this);
    private final ChunkAccessLock lock = new ChunkAccessLock();

    private final AtomicReference<ChunkStep> spawnedStep = new AtomicReference<>();

    private volatile ChunkTask<Unit> upgradeTask;

    public ChunkEntry(
            ChunkPos pos, int level,
            LightingProvider lighting,
            LevelUpdateListener levelUpdateListener,
            PlayersWatchingChunkProvider watchers
    ) {
        super(pos, level, lighting, levelUpdateListener, watchers);

        for (int i = 0; i < this.listeners.length; i++) {
            ChunkListener listener = new ChunkListener();
            this.listeners[i] = listener;
            this.futuresByStatus.set(i, listener.asVanilla());
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
        if (!this.canUpgradeTo(toStep)) {
            return false;
        }

        while (true) {
            ChunkStep fromStep = this.spawnedStep.get();
            if (fromStep != null && fromStep.greaterOrEqual(toStep)) {
                return false;
            }

            if (this.spawnedStep.compareAndSet(fromStep, toStep)) {
                this.combineSavingFuture(toStep);
                return true;
            }
        }
    }

    public boolean canUpgradeTo(ChunkStep toStep) {
        if (this.isUnloaded() || !this.getTargetStep().greaterOrEqual(toStep)) {
            return false;
        }

        ChunkStep currentStep = this.state.getCurrentStep();
        return currentStep == null || !currentStep.greaterOrEqual(toStep);
    }

    public boolean isUnloaded() {
        return ChunkLevelTracker.isUnloaded(this.level);
    }

    public ChunkStep getTargetStep() {
        return getTargetStep(this.level);
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

        for (int i = startIdx; i <= endIdx; i++) {
            this.listeners[i].completeErr();
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

    public void setUpgradeTask(ChunkTask<Unit> task) {
        this.upgradeTask = task;
    }

    @Nullable
    public ChunkTask<Unit> getUpgradeTask() {
        return this.upgradeTask;
    }

    void finishUpgradeTo(ChunkStep step) {
        ChunkStep spawnedStep = this.spawnedStep.get();
        if (step.greaterOrEqual(spawnedStep)) {
            this.upgradeTask = null;
        }
    }

    void combineSavingFuture(ChunkStep step) {
        this.updateFuture(this.getListenerFor(step).asVanilla());
    }

    void combineSavingFuture(Chunk chunk) {
        this.updateFuture(CompletableFuture.completedFuture(Either.left(chunk)));
    }
}
