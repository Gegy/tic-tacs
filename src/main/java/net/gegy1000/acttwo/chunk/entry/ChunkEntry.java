package net.gegy1000.acttwo.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.SharedUnitListener;
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
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkEntry extends ChunkHolder {
    private static final LevelUpdateListener LEVEL_UPDATE_LISTENER = (pos, get, level, set) -> set.accept(level);

    private static final ChunkStatus[] STATUSES = ChunkStatus.createOrderedList().toArray(new ChunkStatus[0]);

    final ChunkEntryListener[] listeners = new ChunkEntryListener[STATUSES.length];

    private final RwLock<ChunkEntryState> state = new RwLock<>(new ChunkEntryState(this));

    private final AtomicReference<ChunkStatus> spawnedStatus = new AtomicReference<>();

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
            this.listeners[i] = new ChunkEntryListener(this);
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

    public ChunkEntryListener getListenerFor(ChunkStatus status) {
        return this.listeners[status.getIndex()];
    }

    @Nullable
    public ChunkStatus getCurrentStatus() {
        return this.state.getInnerUnsafe().getCurrentStatus();
    }

    public boolean trySpawnUpgradeTo(ChunkStatus toStatus) {
        while (true) {
            ChunkStatus fromStatus = this.spawnedStatus.get();
            if (fromStatus != null && fromStatus.isAtLeast(toStatus)) {
                return false;
            }

            if (this.spawnedStatus.compareAndSet(fromStatus, toStatus)) {
                return true;
            }
        }
    }

    public boolean canUpgradeTo(ChunkStatus toStatus) {
        if (!this.getTargetStatus().isAtLeast(toStatus)) {
            return false;
        }

        // safety: status can never be downgraded, so this should always be safe
        ChunkEntryState peekState = this.state.getInnerUnsafe();

        ChunkStatus currentStatus = peekState.getCurrentStatus();
        return currentStatus == null || !currentStatus.isAtLeast(toStatus);
    }

    public ChunkStatus getTargetStatus() {
        return ChunkHolder.getTargetGenerationStatus(this.getLevel());
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
                Future<Unit> future = controller.loader.loadRadius(this.pos, 0, ChunkStatus.FULL);
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
                Future<Unit> future = controller.loader.loadRadius(this.pos, 1, ChunkStatus.FULL);
                controller.spawnOnMainThread(this, future.andThen(unit -> this.write()).map(guard -> {
                    try {
                        ChunkEntryState state = guard.get();
                        state.makeChunkTickable(controller);

                        this.tickableListener.complete();
                    } finally {
                        guard.release();
                    }

                    return Unit.INSTANCE;
                }));
            } else {
                this.tickableListener.reset();
            }
        }

        boolean wasTickingEntities = lastLevelType.isAfter(ChunkHolder.LevelType.ENTITY_TICKING);
        boolean isTickingEntities = currentLevelType.isAfter(ChunkHolder.LevelType.ENTITY_TICKING);
        if (isTickingEntities != wasTickingEntities) {
            if (isTickingEntities) {
                Future<Unit> future = controller.loader.loadRadius(this.pos, 2, ChunkStatus.FULL);
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
        boolean wasLoaded = this.lastTickLevel <= ThreadedAnvilChunkStorage.MAX_LEVEL;
        boolean isLoaded = level <= ThreadedAnvilChunkStorage.MAX_LEVEL;
        if (!wasLoaded) {
            return;
        }

        ChunkStatus lastStatus = ChunkHolder.getTargetGenerationStatus(lastLevel);
        ChunkStatus currentStatus = ChunkHolder.getTargetGenerationStatus(level);

        int startIdx = isLoaded ? currentStatus.getIndex() + 1 : 0;
        int endIdx = lastStatus.getIndex();

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
        return this.getListenerFor(status).vanilla;
    }

    @Override
    @Deprecated
    public CompletableFuture<Either<Chunk, Unloaded>> getNowFuture(ChunkStatus status) {
        return this.getTargetStatus().isAtLeast(status) ? this.getFuture(status) : ChunkHolder.UNLOADED_CHUNK_FUTURE;
    }

    @Override
    @Deprecated
    public CompletableFuture<Chunk> getFuture() {
        ChunkEntryState peekState = this.state.getInnerUnsafe();
        return this.getListenerFor(peekState.getCurrentStatus()).vanilla
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
