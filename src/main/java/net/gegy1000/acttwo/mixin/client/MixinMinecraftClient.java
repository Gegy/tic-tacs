package net.gegy1000.acttwo.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.server.WorldGenerationProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {
    @Inject(
            method = "method_17533",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/MinecraftClient;renderTaskQueue:Ljava/util/Queue;",
                    shift = At.Shift.BEFORE
            ),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void getWorldProgressListener(
            int radius, CallbackInfoReturnable<WorldGenerationProgressListener> ci,
            WorldGenerationProgressTracker tracker
    ) {
        ci.setReturnValue(tracker);
    }
}
