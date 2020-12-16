package net.gegy1000.tictacs.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.QueuingConnection;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.ChunkMap;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.Packet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.collection.TypeFilterableList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

public final class ChunkEntry extends ChunkHolder {
    private final AtomicReferenceArray<ChunkListener> listeners = new AtomicReferenceArray<>(ChunkStep.STEPS.size());

    private volatile ProtoChunk chunk;
    private volatile WorldChunk worldChunk;

    private volatile ChunkStep currentStep;
    private final AtomicReference<ChunkStep> spawnedStep = new AtomicReference<>();
    private final AtomicBoolean loading = new AtomicBoolean();

    private final ChunkEntryTrackers trackers = new ChunkEntryTrackers();
    private final ChunkAccessLock lock = new ChunkAccessLock();

    public ChunkEntry(
            ChunkPos pos, int level,
            LightingProvider lighting,
            LevelUpdateListener levelUpdateListener,
            PlayersWatchingChunkProvider watchers
    ) {
        super(pos, level, lighting, levelUpdateListener, watchers);
    }

    public ChunkAccessLock getLock() {
        return this.lock;
    }

    public ChunkListener getListenerFor(ChunkStep step) {
        while (true) {
            ChunkListener listener = this.listeners.get(step.getIndex());
            if (listener != null) {
                return listener;
            }

            ChunkListener newListener = new ChunkListener(this, step);
            if (this.listeners.compareAndSet(step.getIndex(), null, newListener)) {
                for (ChunkStatus status : step.getStatuses()) {
                    this.futuresByStatus.set(status.getIndex(), newListener.asVanilla());
                }

                return newListener;
            }
        }
    }

    @Nullable
    public ChunkListener getValidListenerFor(ChunkStep step) {
        return this.isValidAs(step) ? this.getListenerFor(step) : null;
    }

    @Nullable
    public ChunkStep getCurrentStep() {
        return this.currentStep;
    }

    public boolean canUpgradeTo(ChunkStep toStep) {
        return this.isValidAs(toStep) && !this.isAt(toStep);
    }

    public boolean isValidAs(ChunkStep toStep) {
        int requiredLevel = ChunkLevelTracker.FULL_LEVEL + ChunkStep.getDistanceFromFull(toStep);
        return this.level <= requiredLevel;
    }

    public ChunkStep getTargetStep() {
        return getTargetStep(this.level);
    }

    public static ChunkStep getTargetStep(int level) {
        int distanceFromFull = level - ChunkLevelTracker.FULL_LEVEL;
        return ChunkStep.byDistanceFromFull(distanceFromFull);
    }

    public boolean trySpawnUpgradeTo(ChunkStep toStep) {
        if (!this.isValidAs(toStep)) {
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

    public boolean trySpawnLoad() {
        return this.loading.compareAndSet(false, true);
    }

    public boolean isTicking() {
        Either<WorldChunk, Unloaded> ticking = this.getTickingFuture().getNow(null);
        if (ticking == null) {
            return false;
        }

        return !ticking.right().isPresent();
    }

    public boolean isTickingEntities() {
        Either<WorldChunk, Unloaded> entityTicking = this.getEntityTickingFuture().getNow(null);
        if (entityTicking == null) {
            return false;
        }

        return !entityTicking.right().isPresent();
    }

    public void onUpdateLevel(ThreadedAnvilChunkStorage tacs) {
        if (this.level > this.lastTickLevel) {
            this.reduceLevel(this.lastTickLevel, this.level);

            ChunkHolder.LevelType level = getLevelType(this.level);
            ChunkHolder.LevelType lastLevel = getLevelType(this.lastTickLevel);

            // TODO: better unify logic that adds & removes from the trackable chunk list
            if (!level.isAfter(LevelType.TICKING) && lastLevel.isAfter(LevelType.TICKING)) {
                ChunkMap map = ((ChunkController) tacs).getMap();
                map.getTickingMaps().removeTrackableChunk(this);
            }
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
            ChunkListener listener = this.listeners.getAndSet(i, null);
            if (listener != null) {
                listener.completeErr();
            }
        }

        this.downgradeSpawnedStep(targetStep);
    }

    private void downgradeSpawnedStep(ChunkStep targetStep) {
        while (true) {
            ChunkStep spawnedStep = this.spawnedStep.get();
            if (targetStep != null && !targetStep.lessThan(spawnedStep)) {
                break;
            }

            if (this.spawnedStep.compareAndSet(spawnedStep, targetStep)) {
                break;
            }
        }
    }

    @Nullable
    public ProtoChunk getProtoChunk() {
        return this.chunk;
    }

    @Nullable
    @Override
    public WorldChunk getWorldChunk() {
        return this.worldChunk;
    }

    @Nullable
    public Chunk getChunk() {
        WorldChunk worldChunk = this.worldChunk;
        if (worldChunk != null) {
            return worldChunk;
        }
        return this.chunk;
    }

    @Nullable
    public Chunk getChunkAtLeast(ChunkStep step) {
        if (this.isAt(step)) {
            return this.getChunk();
        } else {
            return null;
        }
    }

    @Nullable
    public Chunk getChunkForStep(ChunkStep step) {
        if (!this.isAt(step)) {
            return null;
        }

        if (step == ChunkStep.FULL) {
            return this.worldChunk;
        } else {
            return this.chunk;
        }
    }

    public boolean isAt(ChunkStep step) {
        return step.lessOrEqual(this.currentStep);
    }

    public void completeUpgradeOk(ChunkStep step, Chunk chunk) {
        ChunkStep lastStep = this.includeStep(step);

        if (chunk instanceof ProtoChunk) {
            this.chunk = (ProtoChunk) chunk;
        }

        int startIdx = lastStep != null ? lastStep.getIndex() : 0;
        int endIdx = step.getIndex();

        for (int idx = startIdx; idx <= endIdx; idx++) {
            ChunkListener listener = this.listeners.get(idx);
            if (listener != null) {
                listener.completeOk();
            }
        }
    }

    public void notifyUpgradeUnloaded(ChunkStep step) {
        for (int i = step.getIndex(); i < this.listeners.length(); i++) {
            ChunkListener listener = this.listeners.getAndSet(i, null);
            if (listener != null) {
                listener.completeErr();
            }
        }

        this.notifyUpgradeCanceled(step);
    }

    public void notifyUpgradeCanceled(ChunkStep step) {
        this.downgradeSpawnedStep(step.getPrevious());
    }

    @Nullable
    ChunkStep includeStep(ChunkStep step) {
        ChunkStep currentStep = this.currentStep;
        if (step.greaterOrEqual(currentStep)) {
            this.currentStep = step;
        }
        return currentStep;
    }

    void combineSavingFuture(ChunkStep step) {
        this.combineSavingFuture(this.getListenerFor(step).asVanilla());
    }

    void combineSavingFuture(Chunk chunk) {
        this.combineSavingFuture(CompletableFuture.completedFuture(Either.left(chunk)));
    }

    public WorldChunk finalizeChunk(ServerWorld world, LongPredicate loadToWorld) {
        if (this.worldChunk != null) {
            throw new IllegalStateException("chunk already finalized!");
        }

        WorldChunk worldChunk = unwrapWorldChunk(this.chunk);
        if (worldChunk == null) {
            worldChunk = this.upgradeToWorldChunk(world, this.chunk);
        }

        this.worldChunk = worldChunk;
        this.combineSavingFuture(this.worldChunk);

        worldChunk.setLevelTypeProvider(() -> ChunkHolder.getLevelType(this.level));
        worldChunk.loadToWorld();

        if (loadToWorld.test(this.pos.toLong())) {
            worldChunk.setLoadedToWorld(true);
            world.addBlockEntities(worldChunk.getBlockEntities().values());

            Collection<Entity> invalidEntities = this.tryAddEntitiesToWorld(world, worldChunk);
            invalidEntities.forEach(worldChunk::remove);
        }

        worldChunk.disableTickSchedulers();

        return worldChunk;
    }

    private WorldChunk upgradeToWorldChunk(ServerWorld world, ProtoChunk protoChunk) {
        WorldChunk worldChunk = new WorldChunk(world, protoChunk);
        this.chunk = new ReadOnlyChunk(worldChunk);

        return worldChunk;
    }

    private Collection<Entity> tryAddEntitiesToWorld(ServerWorld world, WorldChunk chunk) {
        Collection<Entity> invalidEntities = new ArrayList<>();

        for (TypeFilterableList<Entity> entitySection : chunk.getEntitySectionArray()) {
            for (Entity entity : entitySection) {
                if (entity instanceof PlayerEntity) continue;

                if (!world.loadEntity(entity)) {
                    invalidEntities.add(entity);
                }
            }
        }

        return invalidEntities;
    }

    @Nullable
    private static WorldChunk unwrapWorldChunk(Chunk chunk) {
        if (chunk instanceof ReadOnlyChunk) {
            return ((ReadOnlyChunk) chunk).getWrappedChunk();
        }
        return null;
    }

    public ChunkEntryTrackers getTrackers() {
        return this.trackers;
    }

    @Override
    protected void sendPacketToPlayersWatching(Packet<?> packet, boolean onlyOnWatchDistanceEdge) {
        Set<ServerPlayerEntity> trackingPlayers = this.trackers.getTrackingPlayers();
        if (trackingPlayers.isEmpty()) {
            return;
        }

        if (!onlyOnWatchDistanceEdge) {
            for (ServerPlayerEntity player : trackingPlayers) {
                QueuingConnection.enqueueSend(player.networkHandler, packet);
            }
        } else {
            // pass through TACS to filter the edge
            Stream<ServerPlayerEntity> players = this.playersWatchingChunkProvider.getPlayersWatchingChunk(this.pos, true);
            players.forEach(player -> QueuingConnection.enqueueSend(player.networkHandler, packet));
        }
    }

    @Override
    @Deprecated
    public CompletableFuture<Either<Chunk, Unloaded>> getChunkAt(ChunkStatus status, ThreadedAnvilChunkStorage tacs) {
        return tacs.getChunk(this, status);
    }

    @Override
    @Deprecated
    public CompletableFuture<Either<Chunk, Unloaded>> getFutureFor(ChunkStatus status) {
        ChunkStep step = ChunkStep.byStatus(status);
        return this.getListenerFor(step).asVanilla();
    }

    @Override
    @Deprecated
    public CompletableFuture<Either<Chunk, Unloaded>> getValidFutureFor(ChunkStatus status) {
        ChunkStep step = ChunkStep.byStatus(status);
        return this.isValidAs(step) ? this.getFutureFor(status) : ChunkHolder.UNLOADED_CHUNK_FUTURE;
    }

    @Override
    @Deprecated
    protected void tick(ThreadedAnvilChunkStorage tacs) {
        this.onUpdateLevel(tacs);
    }

    @Override
    @Nullable
    @Deprecated
    public Chunk getCurrentChunk() {
        return this.getProtoChunk();
    }

    @Override
    @Deprecated
    public void setCompletedChunk(ReadOnlyChunk chunk) {
    }

    // TODO: Ideally we can avoid running this logic here, and instead have it be run when we're trying to start/stop chunk tracking
    public boolean isChunkTickable() {
        Set<ServerPlayerEntity> players = this.trackers.getTickableTrackingPlayers();
        if (players.isEmpty()) {
            return false;
        }

        for (ServerPlayerEntity player : players) {
            if (!player.isSpectator()) {
                return true;
            }
        }

        return false;
    }
}
