package net.gegy1000.tictacs.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.server.WorldGenerationProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(
            method = "method_17533",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/atomic/AtomicReference;set(Ljava/lang/Object;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD,
            remap = false
    )
    private void getWorldProgressListener(
            int radius, CallbackInfoReturnable<WorldGenerationProgressListener> ci,
            WorldGenerationProgressTracker tracker
    ) {
        ci.setReturnValue(tracker);
    }
}
