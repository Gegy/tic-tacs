package net.gegy1000.tictacs.mixin;

import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerMixin {
    /**
     * @reason avoid using {@link ThreadedAnvilChunkStorage#entryIterator()} which will create a copy of the list
     * to ensure thread-safety. We're calling from the main thread, so we can safely access the primary chunk map
     * @author gegy1000
     */
    @Redirect(
            method = "tickChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ThreadedAnvilChunkStorage;entryIterator()Ljava/lang/Iterable;"
            )
    )
    @SuppressWarnings("unchecked")
    private Iterable<ChunkHolder> entryIterator(ThreadedAnvilChunkStorage tacs) {
        ChunkAccess chunks = ((ChunkController) tacs).getMap().primary();
        return (ObjectCollection<ChunkHolder>) (ObjectCollection<?>) chunks.getEntries();
    }
}
