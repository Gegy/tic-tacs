package net.gegy1000.acttwo.mixin;

import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ThreadedAnvilChunkStorage.class)
public interface TacsAccessor {
    @Accessor
    ServerWorld getWorld();

    @Accessor
    ChunkGenerator getChunkGenerator();

    @Accessor
    StructureManager getStructureManager();

    @Accessor
    ServerLightingProvider getServerLightingProvider();

    @Accessor
    WorldGenerationProgressListener getWorldGenerationProgressListener();

    @Accessor
    ThreadExecutor<Runnable> getMainThreadExecutor();

    @Accessor
    ThreadedAnvilChunkStorage.TicketManager getTicketManager();
}
