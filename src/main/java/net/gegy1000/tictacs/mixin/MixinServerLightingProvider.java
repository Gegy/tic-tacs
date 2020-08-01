package net.gegy1000.tictacs.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.IntSupplier;

@Mixin(ServerLightingProvider.class)
public abstract class MixinServerLightingProvider extends LightingProvider {
    @Shadow
    @Final
    private TaskExecutor<Runnable> processor;

    private final ObjectList<Runnable> preUpdateQueue = new ObjectArrayList<>();
    private final ObjectList<Runnable> postUpdateQueue = new ObjectArrayList<>();

    private MixinServerLightingProvider(ChunkProvider chunks, boolean blockLight, boolean skyLight) {
        super(chunks, blockLight, skyLight);
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectList;isEmpty()Z")
    )
    private boolean isQueueEmpty(ObjectList<?> queue) {
        return this.preUpdateQueue.isEmpty() && this.postUpdateQueue.isEmpty();
    }

    /**
     * @reason Don't try run tasks when they are being enqueued: leave that to happen from the tick scheduler
     * @author gegy1000
     */
    @Overwrite
    private void enqueue(int x, int z, IntSupplier levelSupplier, ServerLightingProvider.Stage stage, Runnable task) {
        ObjectList<Runnable> queue;
        if (stage == ServerLightingProvider.Stage.PRE_UPDATE) {
            queue = this.preUpdateQueue;
        } else {
            queue = this.postUpdateQueue;
        }

        this.processor.send(() -> queue.add(task));
    }

    /**
     * @reason Remove lighting batch size limit
     * We don't have to worry about overloading the thread-pool because each actor is given its own OS thread.
     * @author gegy1000
     */
    @Overwrite
    private void runTasks() {
        this.runQueue(this.preUpdateQueue);
        super.doLightUpdates(Integer.MAX_VALUE, true, true);
        this.runQueue(this.postUpdateQueue);
    }

    private void runQueue(ObjectList<Runnable> queue) {
        for (Runnable task : queue) {
            task.run();
        }
        queue.clear();
    }
}
