package net.gegy1000.tictacs.client;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.minecraft.client.util.Rect2i;
import net.minecraft.util.math.ChunkPos;

public final class TicTacsDebugLevelTracker {
    public static final TicTacsDebugLevelTracker INSTANCE = new TicTacsDebugLevelTracker();

    private final Long2IntMap levels = new Long2IntOpenHashMap();
    private final Long2LongMap redTime = new Long2LongOpenHashMap();

    private TicTacsDebugLevelTracker() {
        this.levels.defaultReturnValue(ChunkLevelTracker.MAX_LEVEL);
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

    @Environment(EnvType.CLIENT)
    public Rect2i computeBounds() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        LongIterator iterator = this.levels.keySet().iterator();
        while (iterator.hasNext()) {
            long pos = iterator.nextLong();

            int x = ChunkPos.getPackedX(pos);
            int z = ChunkPos.getPackedZ(pos);

            if (x < minX) minX = x;
            if (x > maxX) maxX = x;

            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        return new Rect2i(minX, minZ, maxX - minX, maxZ - minZ);
    }

    public LongCollection chunks() {
        return this.levels.keySet();
    }
}
