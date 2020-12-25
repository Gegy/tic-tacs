package net.gegy1000.tictacs.chunk;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.mixin.TacsAccessor;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.chunk.ChunkStatus;
import org.jetbrains.annotations.Nullable;

public final class ChunkLevelTracker {
    public static final int FULL_LEVEL = 33;
    public static final int MAX_LEVEL = ThreadedAnvilChunkStorage.MAX_LEVEL;

    public static final int LIGHT_TICKET_LEVEL = FULL_LEVEL + ChunkStatus.getDistanceFromFull(ChunkStatus.LIGHT.getPrevious());

    private final ChunkController controller;

    public ChunkLevelTracker(ChunkController controller) {
        this.controller = controller;
    }

    @Nullable
    public ChunkEntry setLevel(long pos, int toLevel, @Nullable ChunkEntry entry, int fromLevel) {
        if (isUnloaded(fromLevel) && isUnloaded(toLevel)) {
            return entry;
        }

        if (entry != null) {
            return this.updateLevel(pos, toLevel, entry);
        } else {
            return this.createAtLevel(pos, toLevel);
        }
    }

    private ChunkEntry updateLevel(long pos, int toLevel, ChunkEntry entry) {
        entry.setLevel(toLevel);

        TacsAccessor accessor = (TacsAccessor) this.controller;
        LongSet unloadedChunks = accessor.getQueuedUnloads();

        if (isUnloaded(toLevel)) {
            unloadedChunks.add(pos);
        } else {
            unloadedChunks.remove(pos);
        }

        return entry;
    }

    @Nullable
    private ChunkEntry createAtLevel(long pos, int toLevel) {
        if (isUnloaded(toLevel)) {
            return null;
        }

        return this.controller.getMap().loadEntry(pos, toLevel);
    }

    public static boolean isLoaded(int level) {
        return level <= MAX_LEVEL;
    }

    public static boolean isUnloaded(int level) {
        return level > MAX_LEVEL;
    }
}
