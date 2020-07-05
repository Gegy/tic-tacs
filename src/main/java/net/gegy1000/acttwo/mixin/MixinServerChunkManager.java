package net.gegy1000.acttwo.mixin;

import com.mojang.datafixers.DataFixer;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(ServerChunkManager.class)
public class MixinServerChunkManager {
    @Shadow
    @Final
    @Mutable
    private ChunkTicketManager ticketManager;

    @Shadow
    @Final
    public ThreadedAnvilChunkStorage threadedAnvilChunkStorage;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ThreadedAnvilChunkStorage;getTicketManager()Lnet/minecraft/server/world/ThreadedAnvilChunkStorage$TicketManager;"
            )
    )
    private ThreadedAnvilChunkStorage.TicketManager getTicketManager(ThreadedAnvilChunkStorage tacs) {
        // we don't have a TACS TicketManager: set it later on
        return null;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(
            ServerWorld world,
            LevelStorage.Session session,
            DataFixer dataFixer,
            StructureManager structures,
            Executor threadPool,
            ChunkGenerator chunkGenerator,
            int viewDistance,
            boolean syncWrite,
            WorldGenerationProgressListener progressListener,
            Supplier<PersistentStateManager> persistentStateSupplier,
            CallbackInfo ci
    ) {
        ChunkController controller = ChunkController.from(this.threadedAnvilChunkStorage);
        this.ticketManager = controller.tracker.leveledTracker;
    }
}
