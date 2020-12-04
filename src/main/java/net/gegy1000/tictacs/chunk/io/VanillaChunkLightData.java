package net.gegy1000.tictacs.chunk.io;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.light.LightingProvider;

public final class VanillaChunkLightData implements ChunkLightData {
    private final ChunkNibbleArray[] blockLightSections = new ChunkNibbleArray[18];
    private final ChunkNibbleArray[] skyLightSections = new ChunkNibbleArray[18];

    @Override
    public void putBlockSection(int y, byte[] data) {
        this.blockLightSections[y + 1] = new ChunkNibbleArray(data);
    }

    @Override
    public void putSkySection(int y, byte[] data) {
        this.skyLightSections[y + 1] = new ChunkNibbleArray(data);
    }

    @Override
    public void applyToWorld(ChunkPos chunkPos, ServerWorld world) {
        LightingProvider lightingProvider = world.getChunkManager().getLightingProvider();

        lightingProvider.setRetainData(chunkPos, true);

        boolean hasSkylight = world.getDimension().hasSkyLight();
        for (int sectionY = -1; sectionY < 17; sectionY++) {
            ChunkSectionPos sectionPos = ChunkSectionPos.from(chunkPos, sectionY);

            ChunkNibbleArray blockLight = this.blockLightSections[sectionY + 1];
            ChunkNibbleArray skyLight = this.skyLightSections[sectionY + 1];

            if (blockLight != null) {
                lightingProvider.enqueueSectionData(LightType.BLOCK, sectionPos, blockLight, true);
            }

            if (hasSkylight && skyLight != null) {
                lightingProvider.enqueueSectionData(LightType.SKY, sectionPos, skyLight, true);
            }
        }
    }

    @Override
    public void applyToChunk(ProtoChunk chunk) {
        chunk.setLightOn(true);
    }
}
