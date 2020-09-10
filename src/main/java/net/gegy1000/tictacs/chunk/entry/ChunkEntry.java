package net.gegy1000.tictacs.chunk.entry;

import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.gegy1000.tictacs.QueuingConnection;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.tictacs.chunk.tracker.ChunkEntityTracker;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

public final class ChunkEntry extends ChunkHolder {
    public static final int FULL_LEVEL = 33;

    public static final int LIGHT_TICKET_LEVEL = FULL_LEVEL + ChunkStep.getDistanceFromFull(ChunkStep.LIGHTING.getPrevious());

    private final AtomicReferenceArray<ChunkListener> listeners = new AtomicReferenceArray<>(ChunkStep.STEPS.size());

    private volatile ProtoChunk chunk;
    private volatile WorldChunk worldChunk;

    private volatile ChunkStep currentStep;
    private final AtomicReference<ChunkStep> spawnedStep = new AtomicReference<>();

    private final ChunkAccessLock lock = new ChunkAccessLock();

    private Set<ServerPlayerEntity> trackingPlayers;
    private Set<ChunkEntityTracker> entities;

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
        return this.isValidAs(toStep) && toStep.greaterThan(this.currentStep);
    }

    public boolean isValidAs(ChunkStep toStep) {
        int requiredLevel = ChunkEntry.FULL_LEVEL + ChunkStep.getDistanceFromFull(toStep);
        return this.level <= requiredLevel;
    }

    public ChunkStep getTargetStep() {
        return getTargetStep(this.level);
    }

    public static ChunkStep getTargetStep(int level) {
        int distanceFromFull = level - FULL_LEVEL;
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
            if (!targetStep.lessThan(spawnedStep)) {
                break;
            }

            if (this.spawnedStep.compareAndSet(spawnedStep, targetStep)) {
                break;
            }
        }
    }

    @Nullable
    public ProtoChunk getChunk() {
        return this.chunk;
    }

    @Nullable
    @Override
    public WorldChunk getWorldChunk() {
        return this.worldChunk;
    }

    @Nullable
    public Chunk getChunkForStep(ChunkStep step) {
        if (step.greaterThan(this.currentStep)) {
            return null;
        }

        if (step == ChunkStep.FULL) {
            return this.worldChunk;
        } else {
            return this.chunk;
        }
    }

    public void completeUpgradeOk(ChunkStep step, Chunk chunk) {
        this.includeStep(step);

        if (chunk instanceof ProtoChunk) {
            this.chunk = (ProtoChunk) chunk;
        }

        ChunkListener listener = this.listeners.get(step.getIndex());
        if (listener != null) {
            listener.completeOk();
        }
    }

    public void notifyUpgradeUnloaded(ChunkStep step) {
        for (int i = step.getIndex(); i < this.listeners.length(); i++) {
            ChunkListener listener = this.listeners.getAndSet(step.getIndex(), null);
            if (listener != null) {
                listener.completeErr();
            }
        }

        ChunkStep lastValidStep = step.getPrevious();
        if (lastValidStep != null) {
            this.downgradeSpawnedStep(lastValidStep);
        }
    }

    void includeStep(ChunkStep step) {
        if (step.greaterOrEqual(this.currentStep)) {
            this.currentStep = step;
        }
    }

    void combineSavingFuture(ChunkStep step) {
        this.combineSavingFuture(this.getListenerFor(step).asVanilla());
    }

    void combineSavingFuture(Chunk chunk) {
        this.combineSavingFuture(CompletableFuture.completedFuture(Either.left(chunk)));
    }

    public WorldChunk finalizeChunk(ServerWorld world, LongPredicate loadToWorld) {
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

    public void addEntity(ChunkEntityTracker tracker) {
        if (this.entities == null) {
            this.entities = new ObjectOpenHashSet<>();
        }
        this.entities.add(tracker);
    }

    public boolean removeEntity(ChunkEntityTracker tracker) {
        if (this.entities != null && this.entities.remove(tracker)) {
            if (this.entities.isEmpty()) {
                this.entities = null;
            }
            return true;
        }
        return false;
    }

    public boolean addTrackingPlayer(ServerPlayerEntity player) {
        if (this.trackingPlayers == null) {
            this.trackingPlayers = new ObjectOpenHashSet<>();
        }

        if (this.trackingPlayers.add(player)) {
            this.startTrackingEntities(player);
            return true;
        }

        return false;
    }

    public boolean removeTrackingPlayer(ServerPlayerEntity player) {
        if (this.trackingPlayers != null && this.trackingPlayers.remove(player)) {
            if (this.trackingPlayers.isEmpty()) {
                this.trackingPlayers = null;
            }

            this.stopTrackingEntities(player);

            return true;
        }

        return false;
    }

    private void startTrackingEntities(ServerPlayerEntity player) {
        if (this.entities != null) {
            for (ChunkEntityTracker tracker : this.entities) {
                tracker.updateTrackerWatched(player);
            }
        }
    }

    private void stopTrackingEntities(ServerPlayerEntity player) {
        if (this.entities != null) {
            for (ChunkEntityTracker tracker : this.entities) {
                tracker.updateTrackerUnwatched(player);
            }
        }
    }

    public Set<ServerPlayerEntity> getTrackingPlayers() {
        return this.trackingPlayers != null ? this.trackingPlayers : Collections.emptySet();
    }

    public boolean isTrackedBy(ServerPlayerEntity player) {
        return this.trackingPlayers != null && this.trackingPlayers.contains(player);
    }

    public Set<ChunkEntityTracker> getEntities() {
        return this.entities != null ? this.entities : Collections.emptySet();
    }

    @Override
    protected void sendPacketToPlayersWatching(Packet<?> packet, boolean onlyOnWatchDistanceEdge) {
        if (this.trackingPlayers == null || this.trackingPlayers.isEmpty()) {
            return;
        }

        if (!onlyOnWatchDistanceEdge) {
            for (ServerPlayerEntity player : this.trackingPlayers) {
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
        return this.getChunk();
    }

    @Override
    @Deprecated
    public void setCompletedChunk(ReadOnlyChunk chunk) {
    }
}
