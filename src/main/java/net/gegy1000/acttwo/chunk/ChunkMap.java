package net.gegy1000.acttwo.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.Collection;

public final class ChunkMap {
    private final Long2ObjectOpenHashMap<ChunkEntry> entries;

    public ChunkMap() {
        this(new Long2ObjectOpenHashMap<>());
    }

    ChunkMap(Long2ObjectOpenHashMap<ChunkEntry> entries) {
        this.entries = entries;
    }

    public void putEntry(ChunkEntry entry) {
        this.entries.put(entry.getPos().toLong(), entry);
    }

    public ChunkEntry removeEntry(long pos) {
        return this.entries.remove(pos);
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

    public ChunkMap copy() {
        return new ChunkMap(this.entries.clone());
    }
}
