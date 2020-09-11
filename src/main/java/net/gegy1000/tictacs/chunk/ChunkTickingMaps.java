package net.gegy1000.tictacs.chunk;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;

import java.util.Collection;

public final class ChunkTickingMaps implements ChunkMapListener {
    private final ReferenceSet<ChunkEntry> trackableEntries = new ReferenceOpenHashSet<>();
    private final ReferenceSet<ChunkEntry> tickableEntries = new ReferenceOpenHashSet<>();

    public void addTrackableChunk(ChunkEntry entry) {
        this.trackableEntries.add(entry);
    }

    public void removeTrackableChunk(ChunkEntry entry) {
        this.trackableEntries.remove(entry);
    }

    public void addTickableChunk(ChunkEntry entry) {
        this.tickableEntries.add(entry);
    }

    public void removeTickableChunk(ChunkEntry entry) {
        this.tickableEntries.remove(entry);
    }

    public Collection<ChunkEntry> getTrackableEntries() {
        return this.trackableEntries;
    }

    public Collection<ChunkEntry> getTickableEntries() {
        return this.tickableEntries;
    }

    @Override
    public void onRemoveChunk(ChunkEntry entry) {
        this.tickableEntries.remove(entry);
        this.trackableEntries.remove(entry);
    }
}
