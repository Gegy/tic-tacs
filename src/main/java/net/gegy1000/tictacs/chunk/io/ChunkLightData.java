package net.gegy1000.tictacs.chunk.io;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ProtoChunk;

public interface ChunkLightData {
    void putBlockSection(int y, byte[] data);

    void putSkySection(int y, byte[] data);

    void applyToWorld(ChunkPos chunkPos, ServerWorld world);

    void applyToChunk(ProtoChunk chunk);
}
