package net.gegy1000.acttwo.chunk.tracker;

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

    public static Entities entities() {
        return new Entities();
    }

    public static void sendPlayerChunk(ServerPlayerEntity player) {
        ChunkSectionPos pos = player.getCameraPosition();
        player.networkHandler.sendPacket(new ChunkRenderDistanceCenterS2CPacket(pos.getSectionX(), pos.getSectionZ()));
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

                this.dataPacket = new ChunkDataS2CPacket(this.chunk, 65535, true);
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
                player.networkHandler.sendPacket(new EntityAttachS2CPacket(entity, entity.getHoldingEntity()));
            }

            for (Entity entity : this.entitiesWithPassengers) {
                player.networkHandler.sendPacket(new EntityPassengersSetS2CPacket(entity));
            }
        }
    }
}
