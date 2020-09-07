package net.gegy1000.tictacs;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

public interface NonBlockingWorldAccess extends WorldView {
    default BlockState getBlockStateIfLoaded(BlockPos pos) {
        if (this.isChunkLoaded(pos)) {
            return this.getBlockState(pos);
        }
        return Blocks.AIR.getDefaultState();
    }

    default FluidState getFluidStateIfLoaded(BlockPos pos) {
        if (this.isChunkLoaded(pos)) {
            return this.getFluidState(pos);
        }
        return Fluids.EMPTY.getDefaultState();
    }
}
