package net.gegy1000.tictacs.chunk;

import net.gegy1000.tictacs.chunk.tracker.ChunkTracker;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;

public interface ChunkController {
    default ThreadedAnvilChunkStorage asTacs() {
        return (ThreadedAnvilChunkStorage) this;
    }

    ChunkMap getMap();

    ChunkTicketManager getTicketManager();

    ChunkTracker getTracker();
}
