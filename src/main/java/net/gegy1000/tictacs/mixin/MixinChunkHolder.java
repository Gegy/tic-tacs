package net.gegy1000.tictacs.mixin;

import net.minecraft.server.world.ChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkHolder.class)
public class MixinChunkHolder {
    /**
     * @reason we replace the future handling in {@link net.gegy1000.tictacs.chunk.entry.ChunkEntry}, and we don't want
     * vanilla's logic to mess with ours.
     * @author gegy1000
     */
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;complete(Ljava/lang/Object;)Z"))
    private <T> boolean complete(CompletableFuture<T> future, T result) {
        return true;
    }
}
