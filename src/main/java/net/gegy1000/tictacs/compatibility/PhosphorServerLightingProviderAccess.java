package net.gegy1000.tictacs.compatibility;

import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.CompletableFuture;

public interface PhosphorServerLightingProviderAccess {
    CompletableFuture<Chunk> setupLightmaps(Chunk chunk);
}
