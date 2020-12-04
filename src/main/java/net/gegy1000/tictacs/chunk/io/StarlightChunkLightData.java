package net.gegy1000.tictacs.chunk.io;

import ca.spottedleaf.starlight.common.chunk.NibbledChunk;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ProtoChunk;

public final class StarlightChunkLightData implements ChunkLightData {
    private final SWMRNibbleArray[] blockLightSections = StarLightEngine.getFilledEmptyLight(false);
    private final SWMRNibbleArray[] skyLightSections = StarLightEngine.getFilledEmptyLight(true);
    private boolean lightOn;

    @Override
    public void putBlockSection(int y, byte[] data) {
        this.blockLightSections[y + 1] = new SWMRNibbleArray(data);
        this.lightOn = true;
    }

    @Override
    public void putSkySection(int y, byte[] data) {
        this.skyLightSections[y + 1] = new SWMRNibbleArray(data);
        this.lightOn = true;
    }

    @Override
    public void applyToWorld(ChunkPos chunkPos, ServerWorld world) {
    }

    @Override
    public void applyToChunk(ProtoChunk chunk) {
        chunk.setLightOn(this.lightOn);

        boolean nullableSky = true;
        for (int y = 16; y >= -1; y--) {
            SWMRNibbleArray nibble = this.skyLightSections[y + 1];
            if (nibble.isNullNibbleUpdating()) {
                nullableSky = false;
            } else if (!nullableSky) {
                nibble.markNonNull();
            }
        }

        NibbledChunk nibbledChunk = (NibbledChunk) chunk;
        nibbledChunk.setBlockNibbles(this.blockLightSections);
        nibbledChunk.setSkyNibbles(this.skyLightSections);
    }
}
