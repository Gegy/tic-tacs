package net.gegy1000.tictacs.mixin;

import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkHolder.class)
public class ChunkHolderMixin {
    /**
     * @reason we replace the future handling in {@link net.gegy1000.tictacs.chunk.entry.ChunkEntry}, and we don't want
     * vanilla's logic to mess with ours.
     * @author gegy1000
     */
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;complete(Ljava/lang/Object;)Z"))
    private <T> boolean complete(CompletableFuture<T> future, T result) {
        return true;
    }

    /**
     * @reason replace with chunk step logic
     * @author gegy1000
     */
    @Overwrite
    public static ChunkStatus getTargetStatusForLevel(int level) {
        return ChunkEntry.getTargetStep(level).getMaximumStatus();
    }
}
