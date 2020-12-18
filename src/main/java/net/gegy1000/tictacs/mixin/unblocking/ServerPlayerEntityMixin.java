package net.gegy1000.tictacs.mixin.unblocking;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin extends Entity {
    private ServerPlayerEntityMixin(EntityType<?> type, World world) {
        super(type, world);
    }

    @Inject(method = "playerTick", at = @At("HEAD"), cancellable = true)
    private void playerTick(CallbackInfo ci) {
        // skip player ticking if chunk is not loaded to replicate thread-blocking behavior in vanilla
        if (!this.world.isChunkLoaded(this.getBlockPos())) {
            ci.cancel();
        }
    }
}
