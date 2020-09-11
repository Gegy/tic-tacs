package net.gegy1000.tictacs.chunk.tracker;

import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkSectionPos;

public final class ChunkTrackWatcher {
    private Consumer startTracking = (player, pos, entry) -> {};
    private Consumer stopTracking = (player, pos, entry) -> {};

    private int radius;

    public ChunkTrackWatcher(int radius) {
        this.radius = radius;
    }

    public void setStartTracking(Consumer startTracking) {
        this.startTracking = startTracking;
    }

    public void setStopTracking(Consumer stopTracking) {
        this.stopTracking = stopTracking;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    public int getRadius() {
        return this.radius;
    }

    public ChunkTrackView viewAt(int x, int z) {
        return ChunkTrackView.withRadius(x, z, this.radius);
    }

    public ChunkTrackView viewAt(ChunkSectionPos pos) {
        return this.viewAt(pos.getX(), pos.getZ());
    }

    void startTracking(ServerPlayerEntity player, long pos, ChunkEntry entry) {
        this.startTracking.accept(player, pos, entry);
    }

    void stopTracking(ServerPlayerEntity player, long pos, ChunkEntry entry) {
        this.stopTracking.accept(player, pos, entry);
    }

    public interface Consumer {
        void accept(ServerPlayerEntity player, long pos, ChunkEntry entry);
    }
}
