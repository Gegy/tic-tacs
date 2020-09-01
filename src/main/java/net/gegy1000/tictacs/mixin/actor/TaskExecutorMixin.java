package net.gegy1000.tictacs.mixin.actor;

import net.gegy1000.tictacs.OwnThreadActor;
import net.minecraft.util.Util;
import net.minecraft.util.thread.TaskExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Executor;

@Mixin(TaskExecutor.class)
public class TaskExecutorMixin<T> {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void create(Executor executor, String name, CallbackInfoReturnable<TaskExecutor<Runnable>> ci) {
        if (executor == Util.getMainWorkerExecutor()) {
            ci.setReturnValue(OwnThreadActor.create(name));
        }
    }
}
