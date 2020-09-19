package net.gegy1000.tictacs.chunk.entry;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.gegy1000.tictacs.chunk.tracker.ChunkEntityTracker;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collections;
import java.util.Set;

public final class ChunkEntryTrackers {
    private Set<ServerPlayerEntity> trackingPlayers;
    private Set<ServerPlayerEntity> tickableTrackingPlayers;
    private Set<ChunkEntityTracker> entities;

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
            this.trackingPlayers = new ObjectOpenHashSet<>(2, Hash.DEFAULT_LOAD_FACTOR);
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

    public boolean addTickableTrackingPlayer(ServerPlayerEntity player) {
        if (this.tickableTrackingPlayers == null) {
            this.tickableTrackingPlayers = new ObjectOpenHashSet<>(2, Hash.DEFAULT_LOAD_FACTOR);
        }

        return this.tickableTrackingPlayers.add(player);
    }

    public boolean removeTickableTrackingPlayer(ServerPlayerEntity player) {
        if (this.tickableTrackingPlayers != null && this.tickableTrackingPlayers.remove(player)) {
            if (this.tickableTrackingPlayers.isEmpty()) {
                this.tickableTrackingPlayers = null;
            }

            return true;
        }

        return false;
    }

    public Set<ServerPlayerEntity> getTrackingPlayers() {
        return this.trackingPlayers != null ? this.trackingPlayers : Collections.emptySet();
    }

    public Set<ServerPlayerEntity> getTickableTrackingPlayers() {
        return this.tickableTrackingPlayers != null ? this.tickableTrackingPlayers : Collections.emptySet();
    }

    public boolean isTrackedBy(ServerPlayerEntity player) {
        return this.trackingPlayers != null && this.trackingPlayers.contains(player);
    }

    public Set<ChunkEntityTracker> getEntities() {
        return this.entities != null ? this.entities : Collections.emptySet();
    }

    public void updateTrackingPlayer(ServerPlayerEntity player) {
        if (this.entities != null) {
            if (!this.isTrackedBy(player)) {
                return;
            }

            for (ChunkEntityTracker entity : this.entities) {
                entity.updateTracker(player);
            }
        }
    }
}
