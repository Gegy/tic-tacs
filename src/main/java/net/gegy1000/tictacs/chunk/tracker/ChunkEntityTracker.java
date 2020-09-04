package net.gegy1000.tictacs.chunk.tracker;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.network.Packet;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;

import java.util.Set;

public final class ChunkEntityTracker {
    private final EntityTrackerEntry entry;
    private final int maxTrackDistance;

    private Set<ServerPlayerEntity> trackingPlayers;

    private ChunkEntry chunkEntry;
    private long chunkPos;

    public ChunkEntityTracker(Entity entity) {
        EntityType<?> type = entity.getType();
        int tickInterval = type.getTrackTickInterval();
        boolean updateVelocity = type.alwaysUpdateVelocity();

        this.entry = new EntityTrackerEntry((ServerWorld) entity.world, entity, tickInterval, updateVelocity, this::sendToTracking);
        this.maxTrackDistance = type.getMaxTrackDistance();
    }

    public Entity getEntity() {
        return this.entry.entity;
    }

    public boolean tick(ChunkController controller) {
        boolean moved = false;

        long chunkPos = chunkForEntity(this.entry.entity);
        if (chunkPos != this.chunkPos || this.chunkEntry == null) {
            ChunkAccess chunks = controller.getMap().primary();

            ChunkEntry fromChunkEntry = this.chunkEntry;
            ChunkEntry toChunkEntry = chunks.getEntry(chunkPos);

            this.chunkEntry = toChunkEntry;
            this.chunkPos = chunkPos;

            this.moveChunk(fromChunkEntry, toChunkEntry);
            moved = true;
        }

        this.entry.tick();

        return moved;
    }

    void remove() {
        if (this.chunkEntry != null) {
            this.chunkEntry.removeEntity(this);
            this.chunkEntry = null;
        }

        if (this.trackingPlayers != null) {
            for (ServerPlayerEntity player : this.trackingPlayers) {
                this.entry.stopTracking(player);
            }
            this.trackingPlayers = null;
        }
    }

    private void moveChunk(ChunkEntry from, ChunkEntry to) {
        if (from != null) {
            this.moveFromChunk(from);
        }

        if (to != null) {
            this.moveToChunk(to);
        }
    }

    private void moveFromChunk(ChunkEntry from) {
        for (ServerPlayerEntity player : from.getTrackingPlayers()) {
            this.updateTrackerUnwatched(player);
        }

        from.removeEntity(this);
    }

    private void moveToChunk(ChunkEntry to) {
        for (ServerPlayerEntity player : to.getTrackingPlayers()) {
            this.updateTrackerWatched(player);
        }

        to.addEntity(this);
    }

    void updateTrackerWatched(ServerPlayerEntity player) {
        if (!this.isTrackedBy(player) && this.canBeTrackedBy(player)) {
            this.startTracking(player);
        }
    }

    void updateTrackerUnwatched(ServerPlayerEntity player) {
        if (this.isTrackedBy(player) && !this.canBeTrackedBy(player)) {
            this.stopTracking(player);
        }
    }

    private void startTracking(ServerPlayerEntity player) {
        if (this.trackingPlayers == null) {
            this.trackingPlayers = new ObjectOpenHashSet<>();
        }

        if (this.trackingPlayers.add(player)) {
            this.entry.startTracking(player);
        }
    }

    private void stopTracking(ServerPlayerEntity player) {
        if (this.trackingPlayers != null && this.trackingPlayers.remove(player)) {
            if (this.trackingPlayers.isEmpty()) {
                this.trackingPlayers = null;
            }

            this.entry.stopTracking(player);
        }
    }

    private boolean isTrackedBy(ServerPlayerEntity player) {
        return this.trackingPlayers != null && this.trackingPlayers.contains(player);
    }

    private boolean canBeTrackedBy(ServerPlayerEntity player) {
        if (player == this.entry.entity) {
            return false;
        } else if (player.teleporting) {
            return true;
        }

        if (this.chunkEntry == null || !this.chunkEntry.isTrackedBy(player)) {
            return false;
        }

        int chunkX = ChunkPos.getPackedX(this.chunkPos);
        int chunkZ = ChunkPos.getPackedZ(this.chunkPos);

        ChunkSectionPos playerChunk = player.getCameraPosition();
        int deltaX = playerChunk.getX() - chunkX;
        int deltaZ = playerChunk.getZ() - chunkZ;

        int distance = Math.max(Math.abs(deltaX), Math.abs(deltaZ));
        return distance < this.getEffectiveTrackDistance();
    }

    public void sendToTrackingAndSelf(Packet<?> packet) {
        this.sendToTracking(packet);
        this.sendToSelf(packet);
    }

    public void sendToTracking(Packet<?> packet) {
        if (this.trackingPlayers == null) {
            return;
        }

        for (ServerPlayerEntity player : this.trackingPlayers) {
            player.networkHandler.sendPacket(packet);
        }
    }

    private void sendToSelf(Packet<?> packet) {
        if (this.entry.entity instanceof ServerPlayerEntity) {
            ServerPlayerEntity player = (ServerPlayerEntity) this.entry.entity;
            player.networkHandler.sendPacket(packet);
        }
    }

    private int getEffectiveTrackDistance() {
        Entity entity = this.entry.entity;
        if (!entity.hasPassengers()) {
            return this.adjustTrackDistance(this.maxTrackDistance);
        }

        int maxDistance = this.maxTrackDistance;
        for (Entity passenger : entity.getPassengersDeep()) {
            maxDistance = Math.max(maxDistance, passenger.getType().getMaxTrackDistance());
        }

        return this.adjustTrackDistance(maxDistance);
    }

    private int adjustTrackDistance(int initialDistance) {
        return this.entry.world.getServer().adjustTrackingDistance(initialDistance);
    }

    private static long chunkForEntity(Entity entity) {
        int x = MathHelper.floor(entity.getX()) >> 4;
        int z = MathHelper.floor(entity.getZ()) >> 4;
        return ChunkPos.toLong(x, z);
    }
}
