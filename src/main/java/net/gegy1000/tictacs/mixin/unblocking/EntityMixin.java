package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Entity.class)
public class EntityMixin {
    @Redirect(
            method = "getLandingPos",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState getBlockStateForLandingPos(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getBlockStateIfReady(pos);
    }

    @Redirect(
            method = "isInsideBubbleColumn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState getBlockStateForBubbleColumn(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getBlockStateIfReady(pos);
    }

    @Redirect(
            method = "updateMovementInFluid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getFluidState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/fluid/FluidState;"
            )
    )
    private FluidState getFluidStateForMovement(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getFluidStateIfReady(pos);
    }

    @Redirect(
            method = "updateSubmergedInWaterState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getFluidState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/fluid/FluidState;"
            )
    )
    private FluidState getFluidStateForSubmergedState(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getFluidStateIfReady(pos);
    }
}
