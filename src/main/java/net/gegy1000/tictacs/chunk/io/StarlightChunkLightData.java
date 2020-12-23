package net.gegy1000.tictacs.chunk.io;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;

public final class StarlightChunkLightData implements ChunkLightData {
    private final SWMRNibbleArray[] blockLightSections = StarLightEngine.getFilledEmptyLight();
    private final SWMRNibbleArray[] skyLightSections = StarLightEngine.getFilledEmptyLight();

    @Override
    public void acceptSection(int y, CompoundTag sectionTag, ChunkStatus status) {
        if (!status.isAtLeast(ChunkStatus.LIGHT)) {
            return;
        }

        if (sectionTag.contains("BlockLight", NbtType.BYTE_ARRAY)) {
            this.blockLightSections[y + 1] = new SWMRNibbleArray(sectionTag.getByteArray("BlockLight").clone());
        }

        if (sectionTag.contains("SkyLight", NbtType.BYTE_ARRAY)) {
            this.skyLightSections[y + 1] = new SWMRNibbleArray(sectionTag.getByteArray("SkyLight").clone());
        } else if (sectionTag.getBoolean("starlight.skylight_uninit")) {
            this.skyLightSections[y + 1] = new SWMRNibbleArray();
        }
    }

    @Override
    public void applyToWorld(ChunkPos chunkPos, ServerWorld world) {
    }

    @Override
    public void applyToChunk(ProtoChunk chunk) {
        ExtendedChunk nibbledChunk = (ExtendedChunk) chunk;
        nibbledChunk.setBlockNibbles(this.blockLightSections);
        nibbledChunk.setSkyNibbles(this.skyLightSections);
    }
}
