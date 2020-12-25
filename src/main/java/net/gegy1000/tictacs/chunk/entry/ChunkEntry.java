package net.gegy1000.tictacs.chunk.entry;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.QueuingConnection;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.ChunkMap;
import net.minecraft.network.Packet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class ChunkEntry extends ChunkHolder {
    private volatile ProtoChunk chunk;
    private volatile WorldChunk worldChunk;

    private volatile ChunkStatus currentStatus;

    private final ChunkEntryTrackers trackers = new ChunkEntryTrackers();

    public ChunkEntry(
            ChunkPos pos, int level,
            LightingProvider lighting,
            LevelUpdateListener levelUpdateListener,
            PlayersWatchingChunkProvider watchers
    ) {
        super(pos, level, lighting, levelUpdateListener, watchers);
    }

    @Override
    @Nullable
    public ChunkStatus getCurrentStatus() {
        return this.currentStatus;
    }

    @Override
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> getChunkAt(ChunkStatus targetStatus, ThreadedAnvilChunkStorage tacs) {
        int index = targetStatus.getIndex();

        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future = this.futuresByStatus.get(index);
        if (future != null) {
            Either<Chunk, ChunkHolder.Unloaded> result = future.getNow(null);
            if (result == null || !result.right().isPresent()) {
                return future;
            }
        }

        if (this.isValidAs(targetStatus)) {
            future = tacs.getChunk(this, targetStatus).thenApply(result -> {
                result.left().ifPresent(chunk -> this.onUpgradeComplete(targetStatus, chunk));
                return result;
            });

            this.combineSavingFuture(future);
            this.futuresByStatus.set(index, future);
            return future;
        } else {
            return future == null ? UNLOADED_CHUNK_FUTURE : future;
        }
    }

    public boolean isValidAs(ChunkStatus toStatus) {
        int requiredLevel = ChunkLevelTracker.FULL_LEVEL + ChunkStatus.getDistanceFromFull(toStatus);
        return this.level <= requiredLevel;
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
    public Chunk getChunkAtLeast(ChunkStatus status) {
        if (this.isAt(status)) {
            return this.getChunk();
        } else {
            return null;
        }
    }

    @Nullable
    public Chunk getChunkForStep(ChunkStatus status) {
        if (!this.isAt(status)) {
            return null;
        }

        if (status == ChunkStatus.FULL) {
            return this.worldChunk;
        } else {
            return this.chunk;
        }
    }

    public boolean isAt(ChunkStatus status) {
        ChunkStatus currentStatus = this.currentStatus;
        return currentStatus != null && currentStatus.isAtLeast(status);
    }

    private void onUpgradeComplete(ChunkStatus status, Chunk chunk) {
        if (chunk instanceof ProtoChunk) {
            this.chunk = (ProtoChunk) chunk;
        }

        if (chunk instanceof ReadOnlyChunk) {
            this.worldChunk = ((ReadOnlyChunk) chunk).getWrappedChunk();
        }

        ChunkStatus currentStatus = this.currentStatus;
        if (currentStatus == null || status.isAtLeast(currentStatus)) {
            this.currentStatus = status;
        }
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
        this.chunk = chunk;
        this.worldChunk = chunk.getWrappedChunk();
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
