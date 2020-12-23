package net.gegy1000.tictacs.chunk.io;

import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.light.LightingProvider;

public final class VanillaChunkLightData implements ChunkLightData {
    private ChunkNibbleArray[] blockLightSections;
    private ChunkNibbleArray[] skyLightSections;

    @Override
    public void acceptSection(int sectionY, CompoundTag sectionTag, ChunkStatus status) {
        if (sectionTag.contains("BlockLight", NbtType.BYTE_ARRAY)) {
            this.putBlockSection(sectionY, sectionTag.getByteArray("BlockLight"));
        }

        if (sectionTag.contains("SkyLight", NbtType.BYTE_ARRAY)) {
            this.putSkySection(sectionY, sectionTag.getByteArray("SkyLight"));
        }
    }

    private void putBlockSection(int y, byte[] data) {
        ChunkNibbleArray[] blockLightSections = this.blockLightSections;
        if (blockLightSections == null) {
            this.blockLightSections = blockLightSections = new ChunkNibbleArray[18];
        }

        blockLightSections[y + 1] = new ChunkNibbleArray(data);
    }

    private void putSkySection(int y, byte[] data) {
        ChunkNibbleArray[] skyLightSections = this.skyLightSections;
        if (skyLightSections == null) {
            this.skyLightSections = skyLightSections = new ChunkNibbleArray[18];
        }

        skyLightSections[y + 1] = new ChunkNibbleArray(data);
    }

    @Override
    public void applyToWorld(ChunkPos chunkPos, ServerWorld world) {
        ChunkNibbleArray[] blockLightSections = this.blockLightSections;
        ChunkNibbleArray[] skyLightSections = this.skyLightSections;
        if (blockLightSections == null && skyLightSections == null) {
            return;
        }

        LightingProvider lightingProvider = world.getChunkManager().getLightingProvider();
        lightingProvider.setRetainData(chunkPos, true);

        boolean hasSkylight = world.getDimension().hasSkyLight();
        for (int sectionY = -1; sectionY < 17; sectionY++) {
            ChunkSectionPos sectionPos = ChunkSectionPos.from(chunkPos, sectionY);

            ChunkNibbleArray blockLight = blockLightSections != null ? blockLightSections[sectionY + 1] : null;
            if (blockLight != null) {
                lightingProvider.enqueueSectionData(LightType.BLOCK, sectionPos, blockLight, true);
            }

            ChunkNibbleArray skyLight = skyLightSections != null ? skyLightSections[sectionY + 1] : null;
            if (hasSkylight && skyLight != null) {
                lightingProvider.enqueueSectionData(LightType.SKY, sectionPos, skyLight, true);
            }
        }
    }

    @Override
    public void applyToChunk(ProtoChunk chunk) {
    }
}
