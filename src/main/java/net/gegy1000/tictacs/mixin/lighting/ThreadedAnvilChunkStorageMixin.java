package net.gegy1000.tictacs.mixin.lighting;

import net.gegy1000.tictacs.VoidActor;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.thread.TaskExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Executor;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageMixin implements ChunkController {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/thread/TaskExecutor;create(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/TaskExecutor;",
                    ordinal = 1
            )
    )
    private TaskExecutor<Runnable> createLightingActor(Executor executor, String name) {
        return new VoidActor(name);
    }
}
