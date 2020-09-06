package net.gegy1000.tictacs.chunk.tracker;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.WorldChunk;

public final class ChunkTracker {
    private final ChunkController controller;

    private final Int2ObjectMap<ChunkEntityTracker> entities = new Int2ObjectOpenHashMap<>();
    private final ChunkPlayerWatchers players;

    private int viewDistance;

    public ChunkTracker(ServerWorld world, ChunkController controller) {
        this.controller = controller;
        this.players = new ChunkPlayerWatchers(world);
    }

    public void setViewDistance(int viewDistance) {
        int lastViewDistance = this.viewDistance;
        this.viewDistance = viewDistance;

        for (ServerPlayerEntity player : this.players.getPlayers()) {
            ChunkSectionPos chunkPos = player.getCameraPosition();

            ChunkTrackView lastView = ChunkTrackView.withRadius(chunkPos.getX(), chunkPos.getZ(), lastViewDistance);
            ChunkTrackView view = ChunkTrackView.withRadius(chunkPos.getX(), chunkPos.getZ(), viewDistance);
            this.updatePlayerTracker(player, lastView, view);
        }
    }

    public void tick() {
        for (ChunkEntityTracker tracker : this.entities.values()) {
            if (tracker.tick(this.controller)) {
                Entity entity = tracker.getEntity();
                if (entity instanceof ServerPlayerEntity) {
                    this.updatePlayerTracker((ServerPlayerEntity) entity);
                }
            }
        }
    }

    private void updatePlayerTracker(ServerPlayerEntity player) {
        ChunkSectionPos lastSectionPos = player.getCameraPosition();
        ChunkSectionPos sectionPos = ChunkSectionPos.from(player);

        long lastChunkPos = ChunkPos.toLong(lastSectionPos.getX(), lastSectionPos.getZ());
        long chunkPos = ChunkPos.toLong(sectionPos.getX(), sectionPos.getZ());

        boolean lastLoadingEnabled = this.players.isLoadingEnabled(player);
        boolean loadingEnabled = this.players.shouldLoadChunks(player);

        if (lastChunkPos == chunkPos && lastLoadingEnabled == loadingEnabled) {
            return;
        }

        ChunkTicketManager ticketManager = this.controller.getTicketManager();
        if (lastChunkPos != chunkPos) {
            player.setCameraPosition(sectionPos);
            ChunkPackets.sendPlayerChunkPos(player);

            if (lastLoadingEnabled) {
                ticketManager.handleChunkLeave(lastSectionPos, player);
            }
            if (loadingEnabled) {
                ticketManager.handleChunkEnter(sectionPos, player);
            }
        }

        if (lastLoadingEnabled != loadingEnabled) {
            this.players.setLoadingEnabled(player, loadingEnabled);

            if (loadingEnabled) {
                ticketManager.handleChunkEnter(sectionPos, player);
            } else {
                ticketManager.handleChunkLeave(lastSectionPos, player);
            }
        }

        int chunkX = sectionPos.getX();
        int chunkZ = sectionPos.getZ();
        int lastChunkX = lastSectionPos.getX();
        int lastChunkZ = lastSectionPos.getZ();

        ChunkTrackView view = ChunkTrackView.withRadius(chunkX, chunkZ, this.viewDistance);
        ChunkTrackView lastView = ChunkTrackView.withRadius(lastChunkX, lastChunkZ, this.viewDistance);
        this.updatePlayerTracker(player, lastView, view);
    }

    private void updatePlayerTracker(ServerPlayerEntity player, ChunkTrackView lastView, ChunkTrackView view) {
        ChunkAccess chunks = this.controller.getMap().primary();
        lastView.forEachDifference(view, pos -> this.stopTrackingChunk(player, chunks, pos));
        view.forEachDifference(lastView, pos -> this.startTrackingChunk(player, chunks, pos));
    }

    private void startTrackingChunk(ServerPlayerEntity player, ChunkAccess chunks, long pos) {
        ChunkEntry entry = chunks.getEntry(pos);
        if (entry == null) {
            return;
        }

        if (entry.addTrackingPlayer(player)) {
            WorldChunk chunk = entry.getWorldChunk();
            if (chunk != null) {
                ChunkPackets.Data dataPackets = ChunkPackets.dataFor(chunk);
                dataPackets.sendTo(player);

                ChunkPackets.Entities entities = ChunkPackets.entitiesFor(entry);
                entities.sendTo(player);
            }
        }
    }

    private void stopTrackingChunk(ServerPlayerEntity player, ChunkAccess chunks, long pos) {
        player.sendUnloadChunkPacket(new ChunkPos(pos));

        ChunkEntry entry = chunks.getEntry(pos);
        if (entry != null) {
            entry.removeTrackingPlayer(player);
        }
    }

    public void addEntity(Entity entity) {
        if (this.entities.containsKey(entity.getEntityId())) {
            return;
        }

        ChunkEntityTracker tracker = new ChunkEntityTracker(entity);
        tracker.tick(this.controller);

        this.entities.put(entity.getEntityId(), tracker);

        if (entity instanceof ServerPlayerEntity) {
            this.addPlayer((ServerPlayerEntity) entity);
        }
    }

    public void removeEntity(Entity entity) {
        ChunkEntityTracker tracker = this.entities.remove(entity.getEntityId());
        if (tracker != null) {
            tracker.remove();

            if (entity instanceof ServerPlayerEntity) {
                this.removePlayer((ServerPlayerEntity) entity);
            }
        }
    }

    private void addPlayer(ServerPlayerEntity player) {
        this.players.addPlayer(player);

        ChunkSectionPos sectionPos = ChunkSectionPos.from(player);
        player.setCameraPosition(sectionPos);
        ChunkPackets.sendPlayerChunkPos(player);

        boolean loadChunks = this.players.shouldLoadChunks(player);
        this.players.setLoadingEnabled(player, loadChunks);
        if (loadChunks) {
            this.controller.getTicketManager().handleChunkEnter(sectionPos, player);
        }

        ChunkAccess chunks = this.controller.getMap().primary();
        ChunkTrackView.withRadius(sectionPos.getX(), sectionPos.getZ(), this.viewDistance).forEach(pos -> {
            this.startTrackingChunk(player, chunks, pos);
        });
    }

    private void removePlayer(ServerPlayerEntity player) {
        ChunkSectionPos sectionPos = player.getCameraPosition();

        boolean loadChunks = this.players.isLoadingEnabled(player);
        if (loadChunks) {
            this.controller.getTicketManager().handleChunkLeave(sectionPos, player);
        }

        this.players.removePlayer(player);

        ChunkAccess chunks = this.controller.getMap().primary();
        ChunkTrackView.withRadius(sectionPos.getX(), sectionPos.getZ(), this.viewDistance).forEach(pos -> {
            this.stopTrackingChunk(player, chunks, pos);
        });
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

    public void onChunkFull(ChunkEntry entry, WorldChunk chunk) {
        ChunkPos chunkPos = entry.getPos();

        for (ServerPlayerEntity player : this.players.getPlayers()) {
            ChunkSectionPos sectionPos = player.getCameraPosition();
            int playerChunkX = sectionPos.getSectionX();
            int playerChunkZ = sectionPos.getSectionZ();

            int distance = Math.max(Math.abs(playerChunkX - chunkPos.x), Math.abs(playerChunkZ - chunkPos.z));
            if (distance <= this.viewDistance) {
                entry.addTrackingPlayer(player);
            }
        }

        ChunkPackets.Data data = ChunkPackets.dataFor(chunk);
        ChunkPackets.Entities entities = ChunkPackets.entitiesFor(entry);

        for (ServerPlayerEntity player : entry.getTrackingPlayers()) {
            data.sendTo(player);
            entities.sendTo(player);
        }
    }
}
