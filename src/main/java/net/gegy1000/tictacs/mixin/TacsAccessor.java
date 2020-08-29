package net.gegy1000.tictacs.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTaskPrioritySystem;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ThreadedAnvilChunkStorage.class)
public interface TacsAccessor {
    @Accessor
    LongSet getLoadedChunks();

    @Accessor("unloadedChunks")
    LongSet getQueuedUnloads();

    @Accessor("chunksToUnload")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> getUnloadingChunks();

    @Accessor
    ChunkTaskPrioritySystem getChunkTaskPrioritySystem();
}
