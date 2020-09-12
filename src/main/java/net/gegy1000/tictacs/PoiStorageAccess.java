package net.gegy1000.tictacs;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;

public interface PoiStorageAccess {
    void initSectionWithPois(ChunkPos pos, ChunkSection section);
}
