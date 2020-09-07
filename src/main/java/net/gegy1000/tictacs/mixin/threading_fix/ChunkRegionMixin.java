package net.gegy1000.tictacs.mixin.threading_fix;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.feature.StructureFeature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.stream.Stream;

@Mixin(ChunkRegion.class)
public class ChunkRegionMixin {
    @Shadow
    @Final
    private ServerWorld world;

    @Unique
    private StructureAccessor structureAccess;

    /**
     * @reason vanilla calls getStructures on the main world object. we don't want to do this! this can cause a race
     * condition where both the main thread and the worker threads are blocking on a chunk to load.
     * @author gegy1000
     */
    @Overwrite
    public Stream<? extends StructureStart<?>> getStructures(ChunkSectionPos pos, StructureFeature<?> feature) {
        if (this.structureAccess == null) {
            this.structureAccess = this.world.getStructureAccessor().forRegion((ChunkRegion) (Object) this);
        }

        return this.structureAccess.getStructuresWithChildren(pos, feature);
    }
}
