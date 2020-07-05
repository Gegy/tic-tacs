package net.gegy1000.acttwo.chunk.tracker;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.minecraft.util.math.ChunkPos;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

public final class ChunkQueues {
    private final ChunkMap map;

    private final Long2ObjectMap<ChunkEntry> loadQueue = new Long2ObjectOpenHashMap<>();
    private final LongSet unloadQueue = new LongOpenHashSet();

    public ChunkQueues(ChunkMap map) {
        this.map = map;
    }

    public ChunkEntry loadEntry(ChunkPos pos, int level) {
        long key = pos.toLong();
        ChunkEntry entry = this.loadQueue.get(key);
        if (entry == null) {
            entry = this.map.createEntry(pos, level);
            this.loadQueue.put(key, entry);
        }

        return entry;
    }

    public void unloadEntry(long pos) {
        this.unloadQueue.add(pos);
        this.loadQueue.remove(pos);
    }

    public void cancelUnloadEntry(long pos) {
        this.unloadQueue.remove(pos);
    }

    public boolean isQueuedForUnload(long pos) {
        return this.unloadQueue.contains(pos);
    }

    public boolean isLoadQueueEmpty() {
        return this.loadQueue.isEmpty();
    }

    public void acceptLoadQueue(Consumer<ChunkEntry> consumer) {
        for (ChunkEntry entry : this.loadQueue.values()) {
            consumer.accept(entry);
        }
        this.loadQueue.clear();
    }

    public void acceptUnloadQueue(LongConsumer consumer) {
        LongIterator iterator = this.unloadQueue.iterator();
        while (iterator.hasNext()) {
            consumer.accept(iterator.nextLong());
        }
        this.unloadQueue.clear();
    }
}
