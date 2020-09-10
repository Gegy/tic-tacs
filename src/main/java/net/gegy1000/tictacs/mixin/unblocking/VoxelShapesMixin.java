package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = VoxelShapes.class, priority = 1001)
public class VoxelShapesMixin {
    @Redirect(
            method = "calculatePushVelocity(Lnet/minecraft/util/math/Box;Lnet/minecraft/world/WorldView;DLnet/minecraft/block/ShapeContext;Lnet/minecraft/util/math/AxisCycleDirection;Ljava/util/stream/Stream;)D",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/WorldView;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private static BlockState getBlockState(WorldView world, BlockPos pos) {
        if (world instanceof NonBlockingWorldAccess) {
            return ((NonBlockingWorldAccess) world).getBlockStateIfLoaded(pos);
        } else {
            return world.getBlockState(pos);
        }
    }
}
