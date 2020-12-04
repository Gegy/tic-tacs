package net.gegy1000.tictacs.mixin.lighting;

import net.gegy1000.tictacs.lighting.LightingExecutor;
import net.gegy1000.tictacs.lighting.LightingExecutorHolder;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;

@Mixin(ServerLightingProvider.class)
public abstract class ServerLightingProviderMixin extends LightingProvider implements LightingExecutorHolder {
    @Unique
    private final LightingExecutor lightingExecutor = new LightingExecutor(this);

    private ServerLightingProviderMixin(ChunkProvider chunks, boolean blockLight, boolean skyLight) {
        super(chunks, blockLight, skyLight);
    }

    /**
     * @reason wake up the executor on each tick for lighting updates that have been indirectly queued
     * @author gegy1000
     */
    @Overwrite
    public void tick() {
        this.lightingExecutor.wake();
    }

    /**
     * @reason delegate to the lighting executor
     * @author gegy1000
     */
    @Overwrite
    private void enqueue(int x, int z, IntSupplier levelSupplier, ServerLightingProvider.Stage stage, Runnable task) {
        this.lightingExecutor.enqueue(task, stage);
    }

    /**
     * @reason allow doLightUpdates to be called from the executor
     * @author gegy1000
     */
    @Override
    @Overwrite
    public int doLightUpdates(int maxUpdateCount, boolean doSkylight, boolean skipEdgeLightPropagation) {
        return super.doLightUpdates(maxUpdateCount, doSkylight, skipEdgeLightPropagation);
    }

    @Inject(method = "close", at = @At("HEAD"))
    public void close(CallbackInfo ci) {
        this.lightingExecutor.close();
    }

    @Override
    public LightingExecutor getLightingExecutor() {
        return this.lightingExecutor;
    }
}
