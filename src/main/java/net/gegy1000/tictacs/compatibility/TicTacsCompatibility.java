package net.gegy1000.tictacs.compatibility;

import net.fabricmc.loader.api.FabricLoader;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.tictacs.chunk.future.FutureHandle;
import net.gegy1000.tictacs.chunk.step.ChunkStepContext;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.CompletableFuture;

public final class TicTacsCompatibility {
    public static final boolean STARLIGHT_LOADED = FabricLoader.getInstance().isModLoaded("starlight");
    public static final boolean PHOSPHOR_LOADED = FabricLoader.getInstance().isModLoaded("phosphor");

    public static Future<Chunk> afterFeaturesStep(ChunkStepContext ctx) {
        if (PHOSPHOR_LOADED) {
            return afterFeaturesStepPhosphor(ctx);
        } else {
            return Future.ready(ctx.chunk);
        }
    }

    private static Future<Chunk> afterFeaturesStepPhosphor(ChunkStepContext ctx) {
        FutureHandle<Chunk> handle = new FutureHandle<>();

        CompletableFuture<Chunk> future = ((PhosphorServerLightingProviderAccess) ctx.lighting).setupLightmaps(ctx.chunk);
        future.thenAccept(handle::complete);

        return handle;
    }
}
