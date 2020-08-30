package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlayerEntity.class)
public class MixinServerPlayerEntity {
    @Redirect(
            method = "handleFall",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;isChunkLoaded(Lnet/minecraft/util/math/BlockPos;)Z"
            )
    )
    private boolean checkChunkLoaded(World world, BlockPos pos) {
        return ((NonBlockingWorldAccess) world).isBlockReady(pos);
    }
}
