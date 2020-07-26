package net.gegy1000.tictacs.client;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;

public final class TicTacsDebugLevelTracker {
    public static final TicTacsDebugLevelTracker INSTANCE = new TicTacsDebugLevelTracker();

    private final Long2IntMap levels = new Long2IntOpenHashMap();
    private final Long2LongMap redTime = new Long2LongOpenHashMap();

    private TicTacsDebugLevelTracker() {
        this.levels.defaultReturnValue(ChunkLevelTracker.MAX_LEVEL + 1);
    }

    public void setLevel(long pos, int level) {
        if (ChunkLevelTracker.isUnloaded(level)) {
            this.levels.remove(pos);
            this.redTime.remove(pos);
            return;
        }

        int prevLevel = this.levels.put(pos, level);
        if (prevLevel != level) {
            this.redTime.put(pos, System.currentTimeMillis() + 2000);
        }
    }

    public int getLevel(long pos) {
        return this.levels.get(pos);
    }

    public long getRedTime(long pos) {
        return this.redTime.get(pos);
    }

    public LongCollection chunks() {
        return this.levels.keySet();
    }
}
