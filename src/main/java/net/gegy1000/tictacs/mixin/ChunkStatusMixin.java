package net.gegy1000.tictacs.mixin;

import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(ChunkStatus.class)
public class ChunkStatusMixin {
    /**
     * @reason replace with ChunkStep values
     * @author gegy1000
     */
    @Overwrite
    public static int getMaxDistanceFromFull() {
        return ChunkStep.getMaxDistance() + 1;
    }

    /**
     * @reason replace with ChunkStep values
     * @author gegy1000
     */
    @Overwrite
    public static ChunkStatus byDistanceFromFull(int distance) {
        return ChunkStep.byDistanceFromFull(distance).getMaximumStatus();
    }

    /**
     * @reason replace with ChunkStep values
     * @author gegy1000
     */
    @Overwrite
    public static int getDistanceFromFull(ChunkStatus status) {
        return ChunkStep.getDistanceFromFull(ChunkStep.byStatus(status));
    }
}
