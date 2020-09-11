package net.gegy1000.tictacs.chunk;

import net.gegy1000.tictacs.chunk.entry.ChunkEntry;

public interface ChunkMapListener {
    default void onAddChunk(ChunkEntry entry) {
    }

    default void onRemoveChunk(ChunkEntry entry) {
    }
}
