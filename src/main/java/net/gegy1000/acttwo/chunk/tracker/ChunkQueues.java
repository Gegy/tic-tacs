package net.gegy1000.acttwo.chunk.tracker;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.gegy1000.acttwo.chunk.ChunkAccess;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.minecraft.util.math.ChunkPos;

import java.util.function.LongConsumer;

public final class ChunkQueues {
    private final ChunkAccess access;
    private final ChunkMap map;
    private final LongSet unloadQueue = new LongOpenHashSet();

    public ChunkQueues(ChunkAccess access) {
        this.access = access;
        this.map = access.getWriteableMap();
    }

    public ChunkEntry loadEntry(ChunkPos pos, int level) {
        ChunkEntry entry = this.map.getEntry(pos);
        if (entry == null) {
            entry = this.access.createEntry(pos, level);
            this.map.putEntry(entry);
        }

        return entry;
    }

    public void unloadEntry(long pos) {
        this.unloadQueue.add(pos);
    }

    public void cancelUnloadEntry(long pos) {
        this.unloadQueue.remove(pos);
    }

    public boolean isQueuedForUnload(long pos) {
        return this.unloadQueue.contains(pos);
    }

    public void acceptUnloadQueue(LongConsumer consumer) {
        LongIterator iterator = this.unloadQueue.iterator();
        while (iterator.hasNext()) {
            consumer.accept(iterator.nextLong());
        }
        this.unloadQueue.clear();
    }
}
