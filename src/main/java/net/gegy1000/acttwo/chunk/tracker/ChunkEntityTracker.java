package net.gegy1000.acttwo.chunk.tracker;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.network.Packet;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

final class ChunkEntityTracker {
    private final ServerWorld world;
    private final ChunkTracker tracker;

    private final Int2ObjectMap<Entry> entities = new Int2ObjectOpenHashMap<>();

    public ChunkEntityTracker(ServerWorld world, ChunkTracker tracker) {
        this.world = world;
        this.tracker = tracker;
    }

    void updatePlayerTracker(ServerPlayerEntity player) {
        for (Entry entry : this.entities.values()) {
            if (entry.entity == player) {
                entry.updateWatchers(this.world.getPlayers());
            } else {
                entry.updateWatcher(player);
            }
        }
    }

    void addEntity(Entity entity) {
        // vanilla special case: don't track ender dragon parts
        if (entity instanceof EnderDragonPart) {
            return;
        }

        if (this.entities.containsKey(entity.getEntityId())) {
            throw new IllegalStateException("entity is already tracked!");
        }

        Entry entityTracker = new Entry(entity);
        this.entities.put(entity.getEntityId(), entityTracker);

        entityTracker.updateWatchers(this.world.getPlayers());
    }

    void removeEntity(Entity entity) {
        Entry entry = this.entities.remove(entity.getEntityId());
        if (entry != null) {
            entry.clearWatchers();
        }
    }

    void addPlayer(ServerPlayerEntity player) {
        for (Entry otherTracker : this.entities.values()) {
            if (otherTracker.entity != player) {
                otherTracker.updateWatcher(player);
            }
        }
    }

    void removePlayer(ServerPlayerEntity player) {
        for (Entry entry : this.entities.values()) {
            entry.removeWatcher(player);
        }
    }

    void sendChunkTrackPackets(ServerPlayerEntity player, ChunkPos chunkPos) {
        ChunkPackets.Entities entities = ChunkPackets.entities();

        for (Entry entry : this.entities.values()) {
            if (entry.entity != player && entry.isInChunk(chunkPos)) {
                entry.updateWatcher(player);
                entities.addEntity(entry.entity);
            }
        }

        entities.sendTo(player);
    }

    private class Entry {
        private final EntityTrackerEntry inner;
        private final Entity entity;

        private final int maxTrackDistance;

        private final Set<ServerPlayerEntity> watchers = new HashSet<>();

        Entry(Entity entity) {
            EntityType<?> type = entity.getType();

            this.inner = new EntityTrackerEntry(
                    (ServerWorld) entity.world,
                    entity, type.getTrackTickInterval(),
                    type.alwaysUpdateVelocity(),
                    this::sendToOtherWatchers
            );

            this.entity = entity;

            this.maxTrackDistance = type.getMaxTrackDistance();
        }

        void sendToOtherWatchers(Packet<?> packet) {
            for (ServerPlayerEntity player : this.watchers) {
                player.networkHandler.sendPacket(packet);
            }
        }

        void sendToWatchers(Packet<?> packet) {
            this.sendToOtherWatchers(packet);

            if (this.entity instanceof ServerPlayerEntity) {
                ServerPlayerEntity player = (ServerPlayerEntity) this.entity;
                player.networkHandler.sendPacket(packet);
            }
        }

        void updateWatcher(ServerPlayerEntity player) {
            if (player == this.entity) return;

            if (this.canPlayerWatch(player)) {
                boolean tracked = this.entity.teleporting || this.canPlayerWatchChunk(player);
                if (tracked && this.watchers.add(player)) {
                    this.inner.startTracking(player);
                }
            } else {
                this.inner.stopTracking(player);
            }
        }

        void updateWatchers(Collection<ServerPlayerEntity> players) {
            for (ServerPlayerEntity player : players) {
                this.updateWatcher(player);
            }
        }

        void clearWatchers() {
            for (ServerPlayerEntity watcher : this.watchers) {
                this.inner.stopTracking(watcher);
            }
            this.watchers.clear();
        }

        void removeWatcher(ServerPlayerEntity watcher) {
            if (this.watchers.remove(watcher)) {
                this.inner.stopTracking(watcher);
            }
        }

        boolean canPlayerWatch(ServerPlayerEntity player) {
            Vec3d lastPos = this.inner.getLastPos();
            double distance = ChunkTracker.getWatchDistance(player.getX(), player.getZ(), lastPos.x, lastPos.z);

            int viewDistance = (ChunkEntityTracker.this.tracker.getWatchDistance() - 1) * 16;
            int trackDistance = Math.min(this.maxTrackDistance, viewDistance);

            return distance < trackDistance && this.entity.canBeSpectated(player);
        }

        boolean canPlayerWatchChunk(ServerPlayerEntity player) {
            int watchedChunkX = this.entity.chunkX;
            int watchedChunkZ = this.entity.chunkZ;

            int playerChunkX = MathHelper.floor(player.getX() / 16.0);
            int playerChunkZ = MathHelper.floor(player.getZ() / 16.0);

            int distance = ChunkTracker.getWatchDistance(watchedChunkX, watchedChunkZ, playerChunkX, playerChunkZ);
            if (distance > ChunkEntityTracker.this.tracker.getWatchDistance()) {
                return false;
            }

            return ChunkEntityTracker.this.tracker.getWorldChunk(watchedChunkX, watchedChunkZ) != null;
        }

        boolean isInChunk(ChunkPos pos) {
            return this.entity.chunkX == pos.x && this.entity.chunkZ == pos.z;
        }
    }
}
