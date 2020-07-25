package net.gegy1000.acttwo.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ThreadedAnvilChunkStorage.class)
public interface TacsAccessor {
    @Accessor
    LongSet getUnloadedChunks();

    @Accessor
    LongSet getLoadedChunks();
}
