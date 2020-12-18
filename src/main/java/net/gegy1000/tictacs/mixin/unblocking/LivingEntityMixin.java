package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
    private LivingEntityMixin(EntityType<?> type, World world) {
        super(type, world);
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void travel(Vec3d movement, CallbackInfo ci) {
        // skip entity travel logic if the current chunk is not loaded
        if (!this.world.isChunkLoaded(this.getBlockPos())) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "getBlockState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState getBlockStateAtEntity(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getBlockStateIfLoaded(pos);
    }

    @Redirect(
            method = "travel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState getBlockStateForMovement(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getBlockStateIfLoaded(pos);
    }

    @Redirect(
            method = "travel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getFluidState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/fluid/FluidState;"
            )
    )
    private FluidState getFluidStateForMovement(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getFluidStateIfLoaded(pos);
    }

    @Inject(method = "applyMovementEffects", at = @At("HEAD"), cancellable = true)
    private void applyMovementEffects(BlockPos pos, CallbackInfo ci) {
        if (!this.world.isChunkLoaded(pos)) {
            ci.cancel();
        }
    }
}
