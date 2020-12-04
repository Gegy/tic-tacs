package net.gegy1000.tictacs.mixin.starlight;

import net.gegy1000.tictacs.lighting.LightingExecutorHolder;
import net.minecraft.server.world.ServerLightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.function.IntSupplier;

@Mixin(value = ServerLightingProvider.class, priority = 1001)
public abstract class StarlightServerLightingProviderMixin implements LightingExecutorHolder {
    /**
     * @author gegy1000
     * @reason redirect starlight's enqueue function to tic-tacs' lighting executor
     */
    @Overwrite
    private void enqueue(int x, int z, Runnable task) {
        this.getLightingExecutor().enqueue(task, ServerLightingProvider.Stage.PRE_UPDATE);
    }

    /**
     * @author gegy1000
     * @reason redirect starlight's enqueue function to tic-tacs' lighting executor
     */
    @Overwrite
    private void enqueue(int x, int z, IntSupplier completedLevelSupplier, boolean postTask, Runnable task) {
        this.enqueue(x, z, task, postTask);
    }

    /**
     * @author gegy1000
     * @reason redirect starlight's enqueue function to tic-tacs' lighting executor
     */
    @Overwrite
    private void enqueue(int x, int z, Runnable task, boolean postTask) {
        this.getLightingExecutor().enqueue(task, postTask ? ServerLightingProvider.Stage.POST_UPDATE : ServerLightingProvider.Stage.PRE_UPDATE);
    }
}
