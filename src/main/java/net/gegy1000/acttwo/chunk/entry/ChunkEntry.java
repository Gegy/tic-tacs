package net.gegy1000.acttwo.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.SharedUnitListener;
import net.gegy1000.acttwo.chunk.step.ChunkStep;
import net.gegy1000.acttwo.chunk.tracker.ChunkLeveledTracker;
import net.gegy1000.acttwo.lock.RwGuard;
import net.gegy1000.acttwo.lock.RwLock;
import net.gegy1000.acttwo.lock.WriteRwGuard;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkEntry extends ChunkHolder {
    public static final int FULL_LEVEL = 33;

    private static final LevelUpdateListener LEVEL_UPDATE_LISTENER = (pos, get, level, set) -> set.accept(level);

    private static final ConcurrentMap<ChunkPos, Future<Unit>> TICKABLE_PENDING = new ConcurrentHashMap<>();

    final ChunkListener[] listeners = new ChunkListener[ChunkStep.STEPS.length];

    private final RwLock<ChunkEntryState> state = RwLock.create(new ChunkEntryState(this));

    private final AtomicReference<ChunkStep> spawnedStep = new AtomicReference<>();

    private final SharedUnitListener accessibleListener = new SharedUnitListener();
    private final SharedUnitListener tickableListener = new SharedUnitListener();
    private final SharedUnitListener entitiesTickableListener = new SharedUnitListener();

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

    public Future<RwGuard<ChunkEntryState>> read() {
        return this.state.read();
    }

    public Future<WriteRwGuard<ChunkEntryState>> write() {
        return this.state.write();
    }

    public RwLock<ChunkEntryState> getState() {
        return this.state;
    }

    public ChunkListener getListenerFor(ChunkStep step) {
        return this.listeners[step.getIndex()];
    }

    @Nullable
    public ChunkStep getCurrentStep() {
        return this.state.getInnerUnsafe().getCurrentStep();
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

        // safety: step can never be downgraded, so this should always be safe
        ChunkEntryState peekState = this.state.getInnerUnsafe();

        ChunkStep currentStep = peekState.getCurrentStep();
        return currentStep == null || !currentStep.greaterOrEqual(toStep);
    }

    public ChunkStep getTargetStep() {
        return getTargetStep(this.getLevel());
    }

    public static ChunkStep getTargetStep(int level) {
        int distanceFromFull = level - FULL_LEVEL;
        return ChunkStep.byDistanceFromFull(distanceFromFull);
    }

    public void onUpdateLevel(ChunkController controller) {
        if (this.level > this.lastTickLevel) {
            this.reduceLevel(this.lastTickLevel, this.level);
        }

        ChunkHolder.LevelType lastLevelType = ChunkHolder.getLevelType(this.lastTickLevel);
        ChunkHolder.LevelType currentLevelType = ChunkHolder.getLevelType(this.level);

        boolean wasAccessible = lastLevelType.isAfter(ChunkHolder.LevelType.BORDER);
        boolean isAccessible = currentLevelType.isAfter(ChunkHolder.LevelType.BORDER);
        this.ticking |= isAccessible;

        if (isAccessible != wasAccessible) {
            if (isAccessible) {
                Future<Unit> future = controller.loader.loadRadius(this.pos, 0, ChunkStep.FULL);
                controller.spawnOnMainThread(this, future.map(unit -> {
                    this.accessibleListener.complete();
                    return unit;
                }));
            } else {
                this.accessibleListener.reset();
            }
        }

        boolean wasTicking = lastLevelType.isAfter(ChunkHolder.LevelType.TICKING);
        boolean isTicking = currentLevelType.isAfter(ChunkHolder.LevelType.TICKING);
        if (isTicking != wasTicking) {
            if (isTicking) {
                Future<Unit> future = controller.loader.loadRadius(this.pos, 1, ChunkStep.FULL);
                Future<Unit> pending = future.andThen(unit -> this.write()).map(guard -> {
                    try {
                        ChunkEntryState state = guard.get();
                        state.makeChunkTickable(controller);
                        controller.map.incrementTickingChunksLoaded();

                        this.tickableListener.complete();
                    } finally {
                        guard.release();
                        TICKABLE_PENDING.remove(this.pos);
                    }

                    return Unit.INSTANCE;
                });

                TICKABLE_PENDING.put(this.pos, pending);
                controller.spawnOnMainThread(this, pending);
            } else {
                this.tickableListener.reset();
            }
        }

        boolean wasTickingEntities = lastLevelType.isAfter(ChunkHolder.LevelType.ENTITY_TICKING);
        boolean isTickingEntities = currentLevelType.isAfter(ChunkHolder.LevelType.ENTITY_TICKING);
        if (isTickingEntities != wasTickingEntities) {
            if (isTickingEntities) {
                Future<Unit> future = controller.loader.loadRadius(this.pos, 2, ChunkStep.FULL);
                controller.spawnOnMainThread(this, future.map(unit -> {
                    this.entitiesTickableListener.complete();
                    return unit;
                }));
            } else {
                this.entitiesTickableListener.reset();
            }
        }

        this.completedLevel = this.level;
        this.lastTickLevel = this.level;
    }

    private void reduceLevel(int lastLevel, int level) {
        boolean wasLoaded = ChunkLeveledTracker.isLoaded(lastLevel);
        if (!wasLoaded) {
            return;
        }

        boolean isLoaded = ChunkLeveledTracker.isLoaded(level);

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
        return this.state.getInnerUnsafe().getWorldChunk();
    }

    public Future<Unit> awaitAccessible() {
        return this.accessibleListener;
    }

    public Future<Unit> awaitTickable() {
        return this.tickableListener;
    }

    public Future<Unit> awaitEntitiesTickable() {
        return this.entitiesTickableListener;
    }

    @Override
    @Deprecated
    protected void tick(ThreadedAnvilChunkStorage chunkStorage) {
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
        ChunkEntryState peekState = this.state.getInnerUnsafe();
        return this.getListenerFor(peekState.getCurrentStep()).vanilla
                .thenApply(result -> {
                    return result.map(chunk -> chunk, err -> null);
                });
    }

    @Override
    @Nullable
    @Deprecated
    public Chunk getCompletedChunk() {
        return this.state.getInnerUnsafe().getChunk();
    }

    @Override
    @Deprecated
    public void method_20456(ReadOnlyChunk chunk) {
    }
}
