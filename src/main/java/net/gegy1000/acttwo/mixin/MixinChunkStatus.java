package net.gegy1000.acttwo.mixin;

import net.gegy1000.acttwo.chunk.step.ChunkStep;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(ChunkStatus.class)
public class MixinChunkStatus {
    /**
     * @reason replace with ChunkStep values
     * @author gegy1000
     */
    @Overwrite
    public static int getMaxTargetGenerationRadius() {
        return ChunkStep.getMaxDistance();
    }

    /**
     * @reason replace with ChunkStep values
     * @author gegy1000
     */
    @Overwrite
    public static ChunkStatus getTargetGenerationStatus(int distance) {
        return ChunkStep.byDistanceFromFull(distance).getMaximumStatus();
    }

    /**
     * @reason replace with ChunkStep values
     * @author gegy1000
     */
    @Overwrite
    public static int getTargetGenerationRadius(ChunkStatus status) {
        return ChunkStep.getDistanceFromFull(ChunkStep.byStatus(status));
    }
}
