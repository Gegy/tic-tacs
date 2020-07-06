package net.gegy1000.acttwo.chunk;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.tracker.ChunkQueues;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

public final class ChunkAccess {
    private final ChunkMap writeableMap = new ChunkMap();
    private ChunkMap map = new ChunkMap();

    private final LongSet fullChunks = new LongOpenHashSet();

    private final ServerWorld world;
    private final ChunkController controller;
    private final ChunkQueues queues;

    public ChunkAccess(ServerWorld world, ChunkController controller) {
        this.world = world;
        this.controller = controller;
        this.queues = new ChunkQueues(this);
    }

    public ChunkEntry createEntry(ChunkPos pos, int level) {
        return new ChunkEntry(pos, level, this.world.getLightingProvider(), this.controller.tracker);
    }

    public ChunkMap getWriteableMap() {
        return this.writeableMap;
    }

    public ChunkMap getMap() {
        return this.map;
    }

    public boolean tryAddFullChunk(ChunkPos pos) {
        return this.fullChunks.add(pos.toLong());
    }

    public boolean tryRemoveFullChunk(ChunkPos pos) {
        return this.fullChunks.remove(pos.toLong());
    }

    public ChunkQueues getQueues() {
        return this.queues;
    }

    public boolean copyIntoReadOnlyMap() {
        this.map = this.writeableMap.copy();
        return false;
    }
}
