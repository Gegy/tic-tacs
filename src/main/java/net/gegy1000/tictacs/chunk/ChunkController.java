package net.gegy1000.tictacs.chunk;

import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.entry.ChunkListener;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.tictacs.chunk.tracker.ChunkTracker;
import net.gegy1000.tictacs.chunk.upgrade.ChunkUpgrader;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

public interface ChunkController {
    default ThreadedAnvilChunkStorage asTacs() {
        return (ThreadedAnvilChunkStorage) this;
    }

    ChunkMap getMap();

    ChunkUpgrader getUpgrader();

    ChunkTicketManager getTicketManager();

    ChunkTracker getTracker();

    ChunkListener getChunkAs(ChunkEntry entry, ChunkStep step);

    Future<Unit> getRadiusAs(ChunkPos pos, int radius, ChunkStep step);

    Future<Chunk> spawnLoadChunk(ChunkEntry entry);

    void notifyStatus(ChunkPos pos, ChunkStatus status);

    <T> void spawnOnMainThread(ChunkEntry entry, Future<T> future);

    default <T> void spawnOnMainThread(Future<T> future) {
        this.spawnOnMainThread(null, future);
    }

    void spawnOnMainThread(ChunkEntry entry, Runnable runnable);
}
