package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow
    public World world;

    @Shadow
    public boolean updateNeeded;

    @Shadow
    private boolean chunkPosUpdateRequested;

    @Shadow
    public abstract BlockPos getBlockPos();

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void move(MovementType type, Vec3d movement, CallbackInfo ci) {
        // skip entity move logic if the current chunk is not loaded
        // this can often otherwise cause the main thread to block while waiting for a chunk to load
        // it is not important for this check to be correct, only that it eliminates most normal cases of blocking

        BlockPos pos = this.getBlockPos();
        if (!this.world.isChunkLoaded(pos) || !this.world.isChunkLoaded(pos.add(movement.x, movement.y, movement.z))) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "getLandingBlockState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState getBlockStateForLandingBlockState(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getBlockStateIfLoaded(pos);
    }

    @Redirect(
            method = "getLandingPos",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState getBlockStateForLandingPos(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getBlockStateIfLoaded(pos);
    }

    @Redirect(
            method = "isInsideBubbleColumn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState getBlockStateForBubbleColumn(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getBlockStateIfLoaded(pos);
    }

    @Redirect(
            method = "updateMovementInFluid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getFluidState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/fluid/FluidState;"
            )
    )
    private FluidState getFluidStateForMovement(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getFluidStateIfLoaded(pos);
    }

    @Redirect(
            method = "updateSubmergedInWaterState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getFluidState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/fluid/FluidState;"
            )
    )
    private FluidState getFluidStateForSubmergedState(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).getFluidStateIfLoaded(pos);
    }

    @Inject(method = "isChunkPosUpdateRequested", at = @At("HEAD"))
    private void isChunkPosUpdateRequested(CallbackInfoReturnable<Boolean> ci) {
        // if we're not added to any chunk, try add us to a chunk
        if (!this.updateNeeded) {
            this.chunkPosUpdateRequested = true;
        }
    }
}
