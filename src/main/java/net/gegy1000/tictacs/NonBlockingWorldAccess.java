package net.gegy1000.tictacs;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

public interface NonBlockingWorldAccess extends WorldView {
    default BlockState getBlockStateIfReady(BlockPos pos) {
        if (this.isBlockReady(pos)) {
            return this.getBlockState(pos);
        }
        return Blocks.AIR.getDefaultState();
    }

    default FluidState getFluidStateIfReady(BlockPos pos) {
        if (this.isBlockReady(pos)) {
            return this.getFluidState(pos);
        }
        return Fluids.EMPTY.getDefaultState();
    }

    default boolean isBlockReady(BlockPos pos) {
        return this.isChunkReady(pos.getX() >> 4, pos.getZ() >> 4);
    }

    boolean isChunkReady(int x, int z);
}
