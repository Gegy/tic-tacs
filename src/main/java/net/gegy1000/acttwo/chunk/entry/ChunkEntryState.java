package net.gegy1000.acttwo.chunk.entry;

import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.step.ChunkStep;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.TypeFilterableList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.WorldChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.LongPredicate;

public final class ChunkEntryState {
    public final ChunkEntry parent;

    private volatile ProtoChunk chunk;
    private volatile WorldChunk worldChunk;

    private volatile ChunkStep step;

    public ChunkEntryState(ChunkEntry parent) {
        this.parent = parent;
    }

    public ChunkPos getPos() {
        return this.parent.getPos();
    }

    @Nullable
    public ProtoChunk getChunk() {
        return this.chunk;
    }

    @Nullable
    public WorldChunk getWorldChunk() {
        return this.worldChunk;
    }

    @Nullable
    public ChunkStep getCurrentStep() {
        return this.step;
    }

    public void completeUpgradeOk(ChunkStep step, Chunk chunk) {
        this.includeStep(step);

        if (chunk instanceof ProtoChunk) {
            this.chunk = (ProtoChunk) chunk;
        }

        for (int i = step.getIndex(); i >= 0; i--) {
            this.parent.listeners[i].completeOk(chunk);
        }
    }

    public void completeUpgradeErr(ChunkStep step, ChunkNotLoadedException err) {
        this.includeStep(step);

        for (int i = step.getIndex(); i >= 0; i--) {
            this.parent.listeners[step.getIndex()].completeErr(err);
        }
    }

    private void includeStep(ChunkStep step) {
        if (this.step == null || step.greaterOrEqual(this.step)) {
            this.step = step;
        }
    }

    private WorldChunk upgradeToWorldChunk(ServerWorld world, ProtoChunk protoChunk) {
        this.worldChunk = new WorldChunk(world, protoChunk);

        ReadOnlyChunk readOnlyChunk = new ReadOnlyChunk(this.worldChunk);
        this.chunk = readOnlyChunk;

        for (ChunkStep step : ChunkStep.STEPS) {
            if (step != ChunkStep.FULL) {
                this.parent.getListenerFor(step).completeOk(readOnlyChunk);
            }
        }

        return this.worldChunk;
    }

    public WorldChunk finalizeChunk(ServerWorld world, LongPredicate loadToWorld) {
        ChunkEntry entry = this.parent;
        ChunkPos pos = entry.getPos();

        WorldChunk worldChunk = unwrapWorldChunk(this.chunk);
        if (worldChunk == null) {
            worldChunk = this.upgradeToWorldChunk(world, this.chunk);
        }

        worldChunk.setLevelTypeProvider(() -> ChunkHolder.getLevelType(entry.getLevel()));
        worldChunk.loadToWorld();

        if (loadToWorld.test(pos.toLong())) {
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

    @Nullable
    private static WorldChunk unwrapWorldChunk(Chunk chunk) {
        if (chunk instanceof ReadOnlyChunk) {
            return ((ReadOnlyChunk) chunk).getWrappedChunk();
        }
        return null;
    }
}
