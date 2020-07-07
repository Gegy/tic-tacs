package net.gegy1000.acttwo.chunk.entry;

import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.TypeFilterableList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.WorldChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Predicate;

public final class ChunkEntryState {
    public final ChunkEntry parent;

    private volatile Chunk chunk;
    private volatile ChunkStatus status;

    public ChunkEntryState(ChunkEntry parent) {
        this.parent = parent;
    }

    public ChunkPos getPos() {
        return this.parent.getPos();
    }

    @Nullable
    public Chunk getChunk() {
        return this.chunk;
    }

    @Nullable
    public WorldChunk getWorldChunk() {
        if (this.chunk instanceof WorldChunk) {
            return (WorldChunk) this.chunk;
        }
        return null;
    }

    public ChunkStatus getCurrentStatus() {
        return this.status;
    }

    public void completeUpgradeOk(ChunkStatus status, Chunk chunk) {
        this.includeStatus(status);
        this.chunk = chunk;

        for (int i = status.getIndex(); i >= 0; i--) {
            this.parent.listeners[i].completeOk(chunk);
        }
    }

    public void completeUpgradeErr(ChunkStatus status, ChunkNotLoadedException err) {
        this.includeStatus(status);

        for (int i = status.getIndex(); i >= 0; i--) {
            this.parent.listeners[status.getIndex()].completeErr(err);
        }
    }

    private void includeStatus(ChunkStatus status) {
        if (this.status == null || status.isAtLeast(this.status)) {
            this.status = status;
        }
    }

    public WorldChunk finalizeChunk(ServerWorld world, Predicate<ChunkPos> loadToWorld) {
        ChunkEntry entry = this.parent;
        ChunkPos pos = entry.getPos();

        WorldChunk worldChunk = unwrapWorldChunk(this.chunk);
        if (worldChunk == null) {
            worldChunk = new WorldChunk(world, (ProtoChunk) this.chunk);
            this.chunk = worldChunk;
            // TODO: vanilla replaces all protochunk listeners with readonlychunk wrapper: should we be doing this?
        }

        worldChunk.setLevelTypeProvider(() -> ChunkHolder.getLevelType(entry.getLevel()));
        worldChunk.loadToWorld();

        if (loadToWorld.test(pos)) {
            worldChunk.setLoadedToWorld(true);
            world.addBlockEntities(worldChunk.getBlockEntities().values());

            Collection<Entity> invalidEntities = this.tryAddEntitiesToWorld(world, worldChunk);
            invalidEntities.forEach(worldChunk::remove);
        }

        worldChunk.disableTickSchedulers();

        return worldChunk;
    }

    private Collection<Entity> tryAddEntitiesToWorld(ServerWorld world, WorldChunk chunk) {
        Collection<Entity> invalidEntities = new ArrayList<>();

        for (TypeFilterableList<Entity> entitySection : chunk.getEntitySectionArray()) {
            for (Entity entity : entitySection) {
                if (entity instanceof PlayerEntity) continue;

                if (!world.loadEntity(entity)) {
                    invalidEntities.add(entity);
                }
            }
        }

        return invalidEntities;
    }

    public void makeChunkTickable(ChunkController controller) {
        WorldChunk chunk = this.getWorldChunk();
        if (chunk == null) return;

        chunk.runPostProcessing();
        controller.tracker.sendChunkToWatchers(this.getPos());
    }

    @Nullable
    private static WorldChunk unwrapWorldChunk(Chunk chunk) {
        if (chunk instanceof ReadOnlyChunk) {
            return ((ReadOnlyChunk) chunk).getWrappedChunk();
        }
        return null;
    }
}
