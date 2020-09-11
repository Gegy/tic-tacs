package net.gegy1000.tictacs.chunk.tracker;

import net.gegy1000.tictacs.QueuingConnection;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkRenderDistanceCenterS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttachS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;

import java.util.ArrayList;
import java.util.List;

public final class ChunkPackets {
    public static Data dataFor(WorldChunk chunk) {
        return new Data(chunk);
    }

    public static Entities entitiesFor(ChunkEntry entry) {
        Entities entities = new Entities();
        for (ChunkEntityTracker tracker : entry.getTrackers().getEntities()) {
            entities.addEntity(tracker.getEntity());
        }

        return entities;
    }

    public static void sendPlayerChunkPos(ServerPlayerEntity player) {
        ChunkSectionPos pos = player.getCameraPosition();
        QueuingConnection.enqueueSend(player.networkHandler, new ChunkRenderDistanceCenterS2CPacket(pos.getSectionX(), pos.getSectionZ()));
    }

    public static class Data {
        private final WorldChunk chunk;

        private ChunkDataS2CPacket dataPacket;
        private LightUpdateS2CPacket lightPacket;

        Data(WorldChunk chunk) {
            this.chunk = chunk;
        }

        public void sendTo(ServerPlayerEntity player) {
            ChunkPos chunkPos = this.chunk.getPos();

            if (this.dataPacket == null) {
                LightingProvider lighting = this.chunk.getWorld().getLightingProvider();

                this.dataPacket = new ChunkDataS2CPacket(this.chunk, 0xFFFF);
                this.lightPacket = new LightUpdateS2CPacket(chunkPos, lighting, true);
            }

            player.sendInitialChunkPackets(chunkPos, this.dataPacket, this.lightPacket);
        }
    }

    public static class Entities {
        private final List<MobEntity> leashedEntities = new ArrayList<>();
        private final List<Entity> entitiesWithPassengers = new ArrayList<>();

        Entities() {
        }

        public void addEntity(Entity entity) {
            if (entity instanceof MobEntity && ((MobEntity) entity).getHoldingEntity() != null) {
                this.leashedEntities.add((MobEntity) entity);
            }

            if (!entity.getPassengerList().isEmpty()) {
                this.entitiesWithPassengers.add(entity);
            }
        }

        public void sendTo(ServerPlayerEntity player) {
            for (MobEntity entity : this.leashedEntities) {
                QueuingConnection.enqueueSend(player.networkHandler, new EntityAttachS2CPacket(entity, entity.getHoldingEntity()));
            }

            for (Entity entity : this.entitiesWithPassengers) {
                QueuingConnection.enqueueSend(player.networkHandler, new EntityPassengersSetS2CPacket(entity));
            }
        }
    }
}
