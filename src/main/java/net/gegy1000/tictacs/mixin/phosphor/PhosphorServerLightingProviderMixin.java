package net.gegy1000.tictacs.mixin.phosphor;

import net.gegy1000.tictacs.compatibility.PhosphorServerLightingProviderAccess;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

@Mixin(value = ServerLightingProvider.class, priority = 999)
public abstract class PhosphorServerLightingProviderMixin implements PhosphorServerLightingProviderAccess {
    // implement with lower priority than phosphor's mixin
    @Override
    public CompletableFuture<Chunk> setupLightmaps(Chunk chunk) {
        return CompletableFuture.completedFuture(chunk);
    }
}
