package net.gegy1000.tictacs.chunk.tracker;

import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkSectionPos;

import org.jetbrains.annotations.Nullable;

public final class ChunkTrackWatcher {
    private Function startTracking;
    private Function stopTracking;
    private Function updateTracking;

    private int radius;

    public ChunkTrackWatcher(int radius) {
        this.radius = radius;
    }

    public void setStartTracking(Function startTracking) {
        this.startTracking = startTracking;
    }

    public void setStopTracking(Function stopTracking) {
        this.stopTracking = stopTracking;
    }

    public void setUpdateTracking(Function updateTracking) {
        this.updateTracking = updateTracking;
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

    @Nullable
    public Function getStartTracking() {
        return this.startTracking;
    }

    @Nullable
    public Function getStopTracking() {
        return this.stopTracking;
    }

    @Nullable
    public Function getUpdateTracking() {
        return this.updateTracking;
    }

    public interface Function {
        void accept(ServerPlayerEntity player, long pos, ChunkEntry entry);
    }
}
