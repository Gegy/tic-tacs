package net.gegy1000.tictacs.mixin.debug;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.TicTacs;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.concurrent.CompletableFuture;

@Mixin(value = ServerChunkManager.class, priority = 2000)
public class ServerChunkManagerMixin {
    private long startBlockTime;
    private Exception blockTrace;

    @Inject(
            method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerChunkManager$MainThreadExecutor;runTasks(Ljava/util/function/BooleanSupplier;)V",
                    shift = At.Shift.BEFORE
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void startRecordBlockingChunk(
            int x, int z, ChunkStatus leastStatus, boolean create, CallbackInfoReturnable<Chunk> ci,
            Profiler profiler, long key, CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future
    ) {
        if (!future.isDone()) {
            this.blockTrace = new Exception();
            this.startBlockTime = System.nanoTime();
        }
    }

    @Inject(
            method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerChunkManager;putInCache(JLnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/chunk/ChunkStatus;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void stopRecordBlockingChunk(int x, int z, ChunkStatus leastStatus, boolean create, CallbackInfoReturnable<Chunk> ci) {
        if (this.blockTrace != null) {
            long timeNs = System.nanoTime() - this.startBlockTime;
            long timeMs = timeNs / 1000000;

            TicTacs.LOGGER.warn("Blocked on chunk for {}ms", timeMs, this.blockTrace);

            this.blockTrace = null;
        }
    }
}
