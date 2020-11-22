package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(World.class)
public abstract class WorldMixin implements WorldAccess, NonBlockingWorldAccess {
    @Shadow
    public abstract Chunk getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create);

    /**
     * TODO: ideally, we don't want to @Overwrite this
     *
     * @reason we don't need to require a FULL chunk, because after the FEATURES step, no more blocks should be changed.
     * this allows us to not block on lighting to retrieve a block
     * @author gegy1000
     */
    @Overwrite
    public BlockState getBlockState(BlockPos pos) {
        if (isOutOfHeightLimit(pos)) {
            return Blocks.VOID_AIR.getDefaultState();
        }

        Chunk chunk = this.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FEATURES, true);
        return chunk.getBlockState(pos);
    }

    /**
     * @reason we don't need to require a FULL chunk, because after the FEATURES step, no more blocks should be changed.
     * this allows us to not block on lighting to retrieve a block
     * @author gegy1000
     */
    @Overwrite
    public FluidState getFluidState(BlockPos pos) {
        if (isOutOfHeightLimit(pos)) {
            return Fluids.EMPTY.getDefaultState();
        }

        Chunk chunk = this.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FEATURES, true);
        return chunk.getFluidState(pos);
    }
}
