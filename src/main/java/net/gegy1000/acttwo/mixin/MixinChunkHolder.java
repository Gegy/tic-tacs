package net.gegy1000.acttwo.mixin;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.AsyncChunkState;
import net.gegy1000.acttwo.chunk.ChunkHolderExt;
import net.gegy1000.acttwo.chunk.TacsExt;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ReadOnlyChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder implements ChunkHolderExt {
    @Shadow
    private int level;

    @Shadow
    protected abstract void updateFuture(CompletableFuture<? extends Either<? extends Chunk, ChunkHolder.Unloaded>> future);

    private final AsyncChunkState asyncState = new AsyncChunkState(this.self());

    private ChunkHolder self() {
        return (ChunkHolder) (Object) this;
    }

    @Override
    public AsyncChunkState getAsyncState() {
        return this.asyncState;
    }

    @Override
    public void combineFuture(CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future) {
        this.updateFuture(future);
    }

    /**
     * @reason replace with implementation that reduces async indirection
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> createFuture(ChunkStatus status, ThreadedAnvilChunkStorage tacs) {
        if (this.shouldUpgradeTo(status)) {
            TacsExt tacsExt = (TacsExt) tacs;
            this.asyncState.upgradeTo(tacs, status, tacsExt::spawnUpgradeFrom);
        }

        return this.asyncState.getListenerFor(status).completable;
    }

    private boolean shouldUpgradeTo(ChunkStatus status) {
        return ChunkHolder.getTargetGenerationStatus(this.level).isAtLeast(status);
    }

    /**
     * @reason replace futuresByStatus usage
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> getFuture(ChunkStatus status) {
        return this.asyncState.getListenerFor(status).completable;
    }

    /**
     * @reason replace futuresByStatus usage
     * @author gegy1000
     */
    @Overwrite
    public void method_20456(ReadOnlyChunk completed) {
        this.asyncState.finalizeAs(completed);
        this.updateFuture(CompletableFuture.completedFuture(Either.left(completed.getWrappedChunk())));
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/datafixers/util/Either;right(Ljava/lang/Object;)Lcom/mojang/datafixers/util/Either;",
                    ordinal = 0,
                    remap = false
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void onReduceLevel(
            ThreadedAnvilChunkStorage storage, CallbackInfo ci,
            ChunkStatus lastStatus, ChunkStatus currentStatus,
            boolean lastNotEmpty, boolean currentNotEmpty,
            ChunkHolder.LevelType lastLevelType, ChunkHolder.LevelType currentLevelType
    ) {
        if (!lastNotEmpty) return;

        currentStatus = currentNotEmpty ? currentStatus : null;

        this.asyncState.reduceStatus(lastStatus, currentStatus);
    }
}
