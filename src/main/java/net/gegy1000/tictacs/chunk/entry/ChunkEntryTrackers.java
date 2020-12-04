package net.gegy1000.tictacs.chunk.entry;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.gegy1000.tictacs.chunk.tracker.ChunkEntityTracker;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collections;
import java.util.Set;

public final class ChunkEntryTrackers {
    private Set<ServerPlayerEntity> trackingPlayers;
    private Set<ServerPlayerEntity> tickableTrackingPlayers;
    private Set<ChunkEntityTracker> entities;

    public void addEntity(ChunkEntityTracker tracker) {
        Set<ChunkEntityTracker> entities = this.entities;
        if (entities == null) {
            this.entities = entities = new ReferenceOpenHashSet<>();
        }
        entities.add(tracker);
    }

    public boolean removeEntity(ChunkEntityTracker tracker) {
        Set<ChunkEntityTracker> entities = this.entities;
        if (entities != null && entities.remove(tracker)) {
            if (entities.isEmpty()) {
                this.entities = null;
            }
            return true;
        }
        return false;
    }

    public boolean addTrackingPlayer(ServerPlayerEntity player) {
        Set<ServerPlayerEntity> trackingPlayers = this.trackingPlayers;
        if (trackingPlayers == null) {
            this.trackingPlayers = trackingPlayers = new ReferenceOpenHashSet<>(2, Hash.DEFAULT_LOAD_FACTOR);
        }

        if (trackingPlayers.add(player)) {
            this.startTrackingEntities(player);
            return true;
        }

        return false;
    }

    public boolean removeTrackingPlayer(ServerPlayerEntity player) {
        Set<ServerPlayerEntity> trackingPlayers = this.trackingPlayers;
        if (trackingPlayers == null) {
            return false;
        }

        if (trackingPlayers.remove(player)) {
            if (trackingPlayers.isEmpty()) {
                this.trackingPlayers = null;
            }

            this.stopTrackingEntities(player);
            return true;
        }

        return false;
    }

    public void updateTrackingPlayer(ServerPlayerEntity player) {
        Set<ChunkEntityTracker> entities = this.entities;
        if (entities != null) {
            Set<ServerPlayerEntity> trackingPlayers = this.trackingPlayers;
            if (trackingPlayers == null || !trackingPlayers.contains(player)) {
                return;
            }

            for (ChunkEntityTracker entity : entities) {
                entity.updateTracker(player);
            }
        }
    }

    private void startTrackingEntities(ServerPlayerEntity player) {
        Set<ChunkEntityTracker> entities = this.entities;
        if (entities != null) {
            for (ChunkEntityTracker tracker : entities) {
                tracker.updateTrackerWatched(player);
            }
        }
    }

    private void stopTrackingEntities(ServerPlayerEntity player) {
        Set<ChunkEntityTracker> entities = this.entities;
        if (entities != null) {
            for (ChunkEntityTracker tracker : entities) {
                tracker.updateTrackerUnwatched(player);
            }
        }
    }

    public boolean addTickableTrackingPlayer(ServerPlayerEntity player) {
        Set<ServerPlayerEntity> tickableTrackingPlayers = this.tickableTrackingPlayers;
        if (tickableTrackingPlayers == null) {
            this.tickableTrackingPlayers = tickableTrackingPlayers = new ReferenceOpenHashSet<>(2, Hash.DEFAULT_LOAD_FACTOR);
        }

        return tickableTrackingPlayers.add(player);
    }

    public boolean removeTickableTrackingPlayer(ServerPlayerEntity player) {
        Set<ServerPlayerEntity> tickableTrackingPlayers = this.tickableTrackingPlayers;
        if (tickableTrackingPlayers != null && tickableTrackingPlayers.remove(player)) {
            if (tickableTrackingPlayers.isEmpty()) {
                this.tickableTrackingPlayers = null;
            }
            return true;
        }

        return false;
    }

    public Set<ServerPlayerEntity> getTrackingPlayers() {
        Set<ServerPlayerEntity> trackingPlayers = this.trackingPlayers;
        return trackingPlayers != null ? trackingPlayers : Collections.emptySet();
    }

    public Set<ServerPlayerEntity> getTickableTrackingPlayers() {
        Set<ServerPlayerEntity> tickableTrackingPlayers = this.tickableTrackingPlayers;
        return tickableTrackingPlayers != null ? tickableTrackingPlayers : Collections.emptySet();
    }

    public boolean isTrackedBy(ServerPlayerEntity player) {
        Set<ServerPlayerEntity> trackingPlayers = this.trackingPlayers;
        return trackingPlayers != null && trackingPlayers.contains(player);
    }

    public Set<ChunkEntityTracker> getEntities() {
        Set<ChunkEntityTracker> entities = this.entities;
        return entities != null ? entities : Collections.emptySet();
    }
}
