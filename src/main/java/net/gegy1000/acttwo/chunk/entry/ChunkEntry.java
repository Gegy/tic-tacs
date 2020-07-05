package net.gegy1000.acttwo.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.UnitListener;
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

public final class ChunkEntry extends ChunkHolder {
    private static final LevelUpdateListener LEVEL_UPDATE_LISTENER = (pos, get, level, set) -> set.accept(level);

    private static final ChunkStatus[] STATUSES = ChunkStatus.createOrderedList().toArray(new ChunkStatus[0]);

    final ChunkEntryListener[] listeners = new ChunkEntryListener[STATUSES.length];

    private final RwLock<ChunkEntryState> state = new RwLock<>(new ChunkEntryState(this));

    private final UnitListener accessibleListener = new UnitListener();
    private final UnitListener tickableListener = new UnitListener();
    private final UnitListener entitiesTickableListener = new UnitListener();

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

    public ChunkEntryListener getListenerFor(ChunkStatus status) {
        return this.listeners[status.getIndex()];
    }

    public boolean canUpgradeTo(ChunkStatus status) {
        if (!this.getTargetStatus().isAtLeast(status)) {
            return false;
        }

        // safety: status can never be downgraded, so this should always be safe
        ChunkEntryState peekState = this.state.getInnerUnsafe();
        return peekState.canUpgradeTo(status);
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
            // TODO: this is wrong. the level type is set before it's actually ready as it should be!
            this.accessibleListener.setComplete(isAccessible);
        }

        boolean wasTicking = lastLevelType.isAfter(ChunkHolder.LevelType.TICKING);
        boolean isTicking = currentLevelType.isAfter(ChunkHolder.LevelType.TICKING);
        if (isTicking != wasTicking) {
            this.tickableListener.setComplete(isTicking);

            if (isTicking) {
                ChunkEntryState state = this.state.getInnerUnsafe();
                state.makeChunkTickable(controller);
            }
        }

        boolean wasTickingEntities = lastLevelType.isAfter(ChunkHolder.LevelType.ENTITY_TICKING);
        boolean isTickingEntities = currentLevelType.isAfter(ChunkHolder.LevelType.ENTITY_TICKING);
        if (isTickingEntities != wasTickingEntities) {
            this.entitiesTickableListener.setComplete(isTickingEntities);
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

    public void finalizeAs(ReadOnlyChunk chunk) {
        for (ChunkEntryListener listener : this.listeners) {
            if (!listener.ok) {
                listener.completeOk(chunk);
            }
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
        return this.getListenerFor(peekState.getStatus()).vanilla
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
        this.finalizeAs(chunk);
    }
}
