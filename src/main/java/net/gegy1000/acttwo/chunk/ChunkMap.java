package net.gegy1000.acttwo.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.tracker.ChunkQueues;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.Collection;

public final class ChunkMap {
    private final Long2ObjectMap<ChunkEntry> entries = new Long2ObjectOpenHashMap<>();
    private final LongSet fullChunks = new LongOpenHashSet();

    private final ServerWorld world;
    private final ChunkController controller;
    private final ChunkQueues queues;

    public ChunkMap(ServerWorld world, ChunkController controller) {
        this.world = world;
        this.controller = controller;
        this.queues = new ChunkQueues(this);
    }

    public ChunkEntry createEntry(ChunkPos pos, int level) {
        return new ChunkEntry(pos, level, this.world.getLightingProvider(), this.controller.tracker);
    }

    public void putEntry(ChunkEntry entry) {
        this.entries.put(entry.getPos().toLong(), entry);
    }

    public ChunkEntry removeEntry(long pos) {
        return this.entries.remove(pos);
    }

    public boolean tryAddFullChunk(ChunkPos pos) {
        return this.fullChunks.add(pos.toLong());
    }

    public boolean tryRemoveFullChunk(ChunkPos pos) {
        return this.fullChunks.remove(pos.toLong());
    }

    @Nullable
    public ChunkEntry getEntry(int chunkX, int chunkZ) {
        return this.entries.get(ChunkPos.toLong(chunkX, chunkZ));
    }

    @Nullable
    public ChunkEntry getEntry(ChunkPos pos) {
        return this.entries.get(pos.toLong());
    }

    @Nullable
    public ChunkEntry getEntry(long pos) {
        return this.entries.get(pos);
    }

    public Collection<ChunkEntry> getEntries() {
        return this.entries.values();
    }

    public ChunkQueues getQueues() {
        return this.queues;
    }

    public boolean loadFromQueues() {
        if (!this.queues.isLoadQueueEmpty()) {
            this.queues.acceptLoadQueue(this::putEntry);
            return true;
        }
        return false;
    }
}
