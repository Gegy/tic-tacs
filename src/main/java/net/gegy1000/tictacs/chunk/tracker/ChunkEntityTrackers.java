package net.gegy1000.tictacs.chunk.tracker;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.server.network.ServerPlayerEntity;

public final class ChunkEntityTrackers {
    private final ChunkController controller;
    private final Int2ObjectMap<ChunkEntityTracker> entities = new Int2ObjectOpenHashMap<>();

    public ChunkEntityTrackers(ChunkController controller) {
        this.controller = controller;
    }

    public void tick() {
        for (ChunkEntityTracker tracker : this.entities.values()) {
            tracker.tick(this.controller);
        }
    }

    public void add(Entity entity) {
        if (this.entities.containsKey(entity.getEntityId())) {
            return;
        }

        ChunkEntityTracker tracker = new ChunkEntityTracker(entity);
        tracker.tick(this.controller);

        this.entities.put(entity.getEntityId(), tracker);

        if (entity instanceof ServerPlayerEntity) {
            this.controller.getTracker().addPlayer((ServerPlayerEntity) entity);
        }
    }

    public void remove(Entity entity) {
        ChunkEntityTracker tracker = this.entities.remove(entity.getEntityId());
        if (tracker != null) {
            tracker.remove();

            if (entity instanceof ServerPlayerEntity) {
                this.controller.getTracker().removePlayer((ServerPlayerEntity) entity);
            }
        }
    }

    public void sendToTracking(Entity entity, Packet<?> packet) {
        ChunkEntityTracker tracker = this.entities.get(entity.getEntityId());
        if (tracker != null) {
            tracker.sendToTracking(packet);
        }
    }

    public void sendToTrackingAndSelf(Entity entity, Packet<?> packet) {
        ChunkEntityTracker tracker = this.entities.get(entity.getEntityId());
        if (tracker != null) {
            tracker.sendToTrackingAndSelf(packet);
        }
    }
}
