package net.gegy1000.tictacs.chunk.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;

public interface ChunkLightData {
    void acceptSection(int sectionY, CompoundTag sectionTag, ChunkStatus status);

    void applyToWorld(ChunkPos chunkPos, ServerWorld world);

    void applyToChunk(ProtoChunk chunk);
}
