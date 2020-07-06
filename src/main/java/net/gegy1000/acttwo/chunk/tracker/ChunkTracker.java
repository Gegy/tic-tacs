package net.gegy1000.acttwo.chunk.tracker;

import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.WorldChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public final class ChunkTracker implements ChunkHolder.PlayersWatchingChunkProvider {
    private static final int MIN_WATCH_DISTANCE = 3;
    private static final int MAX_WATCH_DISTANCE = 33;

    private final ChunkMap map;

    private final ServerWorld world;

    private final ChunkEntityTracker entityTracker;
    private final ChunkPlayerWatchers playerWatchers;

    public final ChunkLeveledTracker leveledTracker;

    private int watchDistance;

    public ChunkTracker(ServerWorld world, ChunkMap map, Executor threadPool, Executor mainThread) {
        this.world = world;
        this.map = map;

        this.entityTracker = new ChunkEntityTracker(world, this);

        this.leveledTracker = new ChunkLeveledTracker(this.map, threadPool, mainThread);
        this.playerWatchers = new ChunkPlayerWatchers(world);
    }

    public static int getWatchDistance(ChunkPos pos, ServerPlayerEntity player) {
        ChunkSectionPos sectionPos = player.getCameraPosition();
        return getWatchDistance(pos.x, pos.z, sectionPos.getX(), sectionPos.getZ());
    }

    public static int getWatchDistance(int fromX, int fromZ, int toX, int toZ) {
        int deltaX = fromX - toX;
        int deltaZ = fromZ - toZ;
        return Math.max(Math.abs(deltaX), Math.abs(deltaZ));
    }

    public static double getWatchDistance(double fromX, double fromZ, double toX, double toZ) {
        double deltaX = fromX - toX;
        double deltaZ = fromZ - toZ;
        return Math.max(Math.abs(deltaX), Math.abs(deltaZ));
    }

    @Nullable
    WorldChunk getWorldChunk(int chunkX, int chunkZ) {
        ChunkEntry entry = this.map.getEntry(chunkX, chunkZ);
        if (entry != null) {
            return entry.getWorldChunk();
        }
        return null;
    }

    public void setWatchDistance(int watchDistance) {
        watchDistance = MathHelper.clamp(watchDistance + 1, MIN_WATCH_DISTANCE, MAX_WATCH_DISTANCE);
        if (watchDistance == this.watchDistance) {
            return;
        }

        int lastWatchDistance = this.watchDistance;
        this.watchDistance = watchDistance;
        this.leveledTracker.setWatchDistance(this.watchDistance);

        for (ChunkEntry entry : this.map.getEntries()) {
            ChunkPos pos = entry.getPos();

            Collection<ServerPlayerEntity> watchers = this.watchersFor(pos);
            if (watchers.isEmpty()) continue;

            ChunkPackets.Data dataPackets = ChunkPackets.dataFor(entry.getWorldChunk());

            for (ServerPlayerEntity player : watchers) {
                if (player.world != this.world) continue;

                int distance = ChunkTracker.getWatchDistance(pos, player);
                boolean lastTracked = distance <= lastWatchDistance;
                boolean currentTracked = distance <= watchDistance;
                if (currentTracked != lastTracked) {
                    if (currentTracked) {
                        dataPackets.sendTo(player);
                        this.entityTracker.sendChunkTrackPackets(player, entry.getPos());
                    } else {
                        player.sendUnloadChunkPacket(pos);
                    }
                }
            }
        }
    }

    public void addEntity(Entity entity) {
        this.entityTracker.addEntity(entity);

        if (entity instanceof ServerPlayerEntity) {
            this.addPlayer((ServerPlayerEntity) entity);
        }
    }

    public void removeEntity(Entity entity) {
        this.entityTracker.removeEntity(entity);

        if (entity instanceof ServerPlayerEntity) {
            this.removePlayer((ServerPlayerEntity) entity);
        }
    }

    void addPlayer(ServerPlayerEntity player) {
        if (this.playerWatchers.containsPlayer(player)) {
            return;
        }

        this.entityTracker.addPlayer(player);
        this.playerWatchers.addPlayer(player);

        ChunkSectionPos currentSection = ChunkSectionPos.from(player);
        player.setCameraPosition(currentSection);
        ChunkPackets.sendPlayerChunk(player);

        if (this.playerWatchers.isLoadingEnabled(player)) {
            this.leveledTracker.handleChunkEnter(currentSection, player);
        }

        ChunkTrackKernel.withRadius(player, this.watchDistance).forEach(pos -> this.sendWatchPackets(player, pos));
    }

    void removePlayer(ServerPlayerEntity player) {
        if (!this.playerWatchers.containsPlayer(player)) {
            return;
        }

        if (this.playerWatchers.isLoadingEnabled(player)) {
            ChunkSectionPos currentSection = player.getCameraPosition();
            this.leveledTracker.handleChunkLeave(currentSection, player);
        }

        this.entityTracker.removePlayer(player);
        this.playerWatchers.removePlayer(player);

        ChunkTrackKernel.withRadius(player, this.watchDistance).forEach(player::sendUnloadChunkPacket);
    }

    public void updatePlayerTracker(ServerPlayerEntity player) {
        if (!this.playerWatchers.containsPlayer(player)) {
            return;
        }

        this.entityTracker.updatePlayerTracker(player);

        ChunkSectionPos lastTrackSection = player.getCameraPosition();
        ChunkSectionPos currentTrackSection = ChunkSectionPos.from(player);

        boolean wasLoadingEnabled = !this.playerWatchers.isLoadingEnabled(player);
        boolean isLoadingEnabled = this.playerWatchers.shouldLoadChunks(player);

        boolean movedSection = lastTrackSection.asLong() != currentTrackSection.asLong();
        if (movedSection) {
            player.setCameraPosition(currentTrackSection);
            ChunkPackets.sendPlayerChunk(player);

            if (wasLoadingEnabled) {
                this.leveledTracker.handleChunkLeave(lastTrackSection, player);
            }
            if (isLoadingEnabled) {
                this.leveledTracker.handleChunkEnter(currentTrackSection, player);
            }
        }

        if (wasLoadingEnabled != isLoadingEnabled) {
            this.playerWatchers.setLoadingEnabled(player, isLoadingEnabled);
            if (isLoadingEnabled) {
                this.leveledTracker.handleChunkEnter(currentTrackSection, player);
            } else {
                this.leveledTracker.handleChunkLeave(lastTrackSection, player);
            }
        }

        if (!movedSection && wasLoadingEnabled == isLoadingEnabled) {
            return;
        }

        int currentChunkX = currentTrackSection.getSectionX();
        int currentChunkZ = currentTrackSection.getSectionZ();
        int lastChunkX = lastTrackSection.getSectionX();
        int lastChunkZ = lastTrackSection.getSectionZ();

        ChunkTrackKernel currentKernel = ChunkTrackKernel.withRadius(currentTrackSection, this.watchDistance);
        ChunkTrackKernel lastKernel = ChunkTrackKernel.withRadius(lastTrackSection, this.watchDistance);

        if (currentKernel.intersects(lastKernel)) {
            currentKernel.union(lastKernel).forEach(pos -> {
                boolean wasTracked = getWatchDistance(pos.x, pos.z, lastChunkX, lastChunkZ) <= this.watchDistance;
                boolean isTracked = getWatchDistance(pos.x, pos.z, currentChunkX, currentChunkZ) <= this.watchDistance;
                if (isTracked != wasTracked) {
                    if (isTracked) {
                        this.sendWatchPackets(player, pos);
                    } else {
                        this.sendUnwatchPackets(player, pos);
                    }
                }
            });
        } else {
            lastKernel.forEach(pos -> this.sendUnwatchPackets(player, pos));
            currentKernel.forEach(pos -> this.sendWatchPackets(player, pos));
        }
    }

    public void sendChunkToWatchers(ChunkPos pos) {
        ChunkEntry entry = this.map.getEntry(pos);
        if (entry == null) return;

        Collection<ServerPlayerEntity> watchers = this.watchersFor(pos);

        WorldChunk chunk = this.getWorldChunk(pos.x, pos.z);
        if (chunk != null) {
            ChunkPackets.Data dataPackets = ChunkPackets.dataFor(chunk);

            for (ServerPlayerEntity player : watchers) {
                dataPackets.sendTo(player);
                this.entityTracker.sendChunkTrackPackets(player, entry.getPos());
            }
        }
    }

    private void sendWatchPackets(ServerPlayerEntity player, ChunkPos pos) {
        ChunkEntry entry = this.map.getEntry(pos);
        if (entry == null) return;

        WorldChunk chunk = this.getWorldChunk(pos.x, pos.z);
        if (chunk != null) {
            ChunkPackets.Data dataPackets = ChunkPackets.dataFor(chunk);
            dataPackets.sendTo(player);

            this.entityTracker.sendChunkTrackPackets(player, entry.getPos());
        }
    }

    private void sendUnwatchPackets(ServerPlayerEntity player, ChunkPos pos) {
        player.sendUnloadChunkPacket(pos);
    }

    public Collection<ServerPlayerEntity> watchersFor(ChunkPos chunkPos) {
        return this.watchersFor(chunkPos, false);
    }

    public Collection<ServerPlayerEntity> watchersFor(ChunkPos chunkPos, boolean onlyEdge) {
        if (this.playerWatchers.isEmpty()) {
            return Collections.emptyList();
        }

        List<ServerPlayerEntity> watchers = new ArrayList<>();
        for (ServerPlayerEntity player : this.playerWatchers.getPlayers()) {
            if (this.canWatchChunk(player, chunkPos, onlyEdge)) {
                watchers.add(player);
            }
        }

        return watchers;
    }

    @Override
    public Stream<ServerPlayerEntity> getPlayersWatchingChunk(ChunkPos chunkPos, boolean onlyEdge) {
        if (this.playerWatchers.isEmpty()) {
            return Stream.empty();
        }

        return this.playerWatchers.getPlayers().stream()
                .filter(player -> this.canWatchChunk(player, chunkPos, onlyEdge));
    }

    public boolean canWatchChunk(ServerPlayerEntity player, ChunkPos chunkPos, boolean onlyEdge) {
        int watchDistance = getWatchDistance(chunkPos, player);
        if (watchDistance > this.watchDistance) {
            return false;
        }

        return !onlyEdge || watchDistance == this.watchDistance;
    }

    public int getWatchDistance() {
        return this.watchDistance;
    }
}
