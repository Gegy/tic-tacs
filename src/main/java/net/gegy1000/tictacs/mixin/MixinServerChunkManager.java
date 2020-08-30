package net.gegy1000.tictacs.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Mixin(ServerChunkManager.class)
public abstract class MixinServerChunkManager {
    @Shadow
    protected abstract CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> getChunkFuture(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create);

    @Shadow
    @Final
    private ServerChunkManager.MainThreadExecutor mainThreadExecutor;

    /**
     * @reason we don't want to block the main thread when we're trying to retrieve a chunk from off-thread
     * @author gegy1000
     */
    @Inject(
            method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
                    shift = At.Shift.BEFORE
            ),
            cancellable = true
    )
    private void getChunkOffThread(int x, int z, ChunkStatus leastStatus, boolean create, CallbackInfoReturnable<Chunk> ci) {
        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future = CompletableFuture.supplyAsync(() -> this.getChunkFuture(x, z, leastStatus, create), this.mainThreadExecutor)
                .thenCompose(Function.identity());

        Either<Chunk, ChunkHolder.Unloaded> result = future.join();
        ci.setReturnValue(result.map(
                chunk -> chunk,
                unloaded -> {
                    if (create) {
                        throw new IllegalStateException("Chunk not there when requested: " + unloaded);
                    } else {
                        return null;
                    }
                })
        );
    }
}
