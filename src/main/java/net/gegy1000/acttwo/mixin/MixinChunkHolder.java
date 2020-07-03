package net.gegy1000.acttwo.mixin;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.ChunkHolderExt;
import net.gegy1000.acttwo.chunk.ChunkListener;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.TacsExt;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;

// TODO: issues
//    1. we need to get/setCompletedLevel: this is only used in lighting, but traditionally it is set when the sorter
//      actor thread processes the level change. not sure where the ideal place to replicate that is here
//    2. we have a memory leak! old tasks need to be properly discarded
//      vanilla doesn't handle cancellation of tasks: but we might need to. we don't need to keep track of tasks
//      in relation to chunk positions. rather complete the root listeners with a not loaded status and catch the
//      waker to not re-enqueue the task
//    3. relating to the memory leak: unloading does not work well. because we enqueue all chunks up to their maximum
//      up-front, it waits for the chunk future to complete before unloading. not sure what the best approach is here
//    4. random freezes. no idea why they are happening- most likely a dependency loop issue
@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder implements ChunkHolderExt {
    private static final List<ChunkStatus> STATUSES = ChunkStatus.createOrderedList();

    @Shadow
    @Final
    private ChunkPos pos;

    @Shadow
    private int level;

    @Shadow
    @Final
    @Mutable
    private AtomicReferenceArray<CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>>> futuresByStatus;

    private final ChunkListener[] listeners = new ChunkListener[STATUSES.size()];

    private final Object mutex = new Object();

    private final ChunkListener mainListener = new ChunkListener();
    private ChunkStatus desiredStatus;

    @Inject(method = "<init>", at = @At("HEAD"))
    private void init(
            ChunkPos pos, int level,
            LightingProvider lighting,
            ChunkHolder.LevelUpdateListener levelUpdateListener,
            ChunkHolder.PlayersWatchingChunkProvider watching,
            CallbackInfo ci
    ) {
        this.futuresByStatus = null;

        for (ChunkStatus status : STATUSES) {
            this.listeners[status.getIndex()] = new ChunkListener();
        }
    }

    /**
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> createFuture(ChunkStatus status, ThreadedAnvilChunkStorage tacs) {
        this.tryUpgradeTo(tacs, status);
        return this.getListenerFor(status).completable;
    }

    /**
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> getFuture(ChunkStatus status) {
        return this.getListenerFor(status).completable;
    }

    @Override
    public void tryUpgradeTo(ThreadedAnvilChunkStorage tacs, ChunkStatus status) {
        if (!this.shouldUpgradeTo(status)) {
            return;
        }

        // TODO: can we avoid locking?
        synchronized (this.mutex) {
            if (this.desiredStatus != null) {
                if (this.desiredStatus.isAtLeast(status)) {
                    return;
                }
                this.upgradeFrom(tacs, this.desiredStatus, status);
            } else {
                this.upgradeFromRoot(tacs, status);
            }

            this.beginLoading(status);
        }
    }

    @Override
    public ChunkListener getListenerFor(ChunkStatus status) {
        return this.listeners[status.getIndex()];
    }

    private boolean shouldUpgradeTo(ChunkStatus status) {
        return ChunkHolder.getTargetGenerationStatus(this.level).isAtLeast(status);
    }

    private void upgradeFrom(ThreadedAnvilChunkStorage tacs, ChunkStatus fromStatus, ChunkStatus toStatus) {
        ((TacsExt) tacs).spawnUpgradeFrom(this.mainListener, this.self(), fromStatus, toStatus);
    }

    private void upgradeFromRoot(ThreadedAnvilChunkStorage tacs, ChunkStatus status) {
        Future<Chunk> load = ((TacsExt) tacs).spawnLoadChunk(this.self());
        if (status != ChunkStatus.EMPTY) {
            ((TacsExt) tacs).spawnUpgradeFrom(load, this.self(), ChunkStatus.EMPTY, status);
        }
    }

    private void beginLoading(ChunkStatus status) {
        if (status.isAtLeast(status)) {
            this.desiredStatus = status;
            this.updateFuture(this.mainListener.completable);
        }
    }

    @Override
    public void complete(ChunkStatus status, Either<Chunk, ChunkHolder.Unloaded> result) {
        if (this.desiredStatus != null && this.desiredStatus.isAtLeast(status)) {
            this.mainListener.complete(result);
        }

        while (status != null) {
            this.getListenerFor(status).complete(result);
            status = prevOrNull(status);
        }
    }

    @Nullable
    private static ChunkStatus prevOrNull(ChunkStatus status) {
        return status == ChunkStatus.EMPTY ? null : status.getPrevious();
    }

    /**
     * @author gegy1000
     */
    @Overwrite
    public void method_20456(ReadOnlyChunk completed) {
        for (ChunkListener listener : this.listeners) {
            listener.modify(result -> {
                if (result instanceof ProtoChunk) {
                    return completed;
                } else {
                    return result;
                }
            });
        }

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
            ChunkStatus fromStatus, ChunkStatus toStatus,
            boolean fromEmpty, boolean toEmpty,
            ChunkHolder.LevelType lastLevelType, ChunkHolder.LevelType currentLevelType
    ) {
        if (!fromEmpty) return;

        int startIdx = toEmpty ? toStatus.getIndex() + 1 : 0;
        int endIdx = fromStatus.getIndex();

        if (startIdx > endIdx) return;

        ChunkNotLoadedException unloaded = new ChunkNotLoadedException(this.pos);
        for (int i = startIdx; i <= endIdx; i++) {
            this.listeners[i].completeErr(unloaded);
        }
    }

    private ChunkHolder self() {
        return (ChunkHolder) (Object) this;
    }

    @Shadow
    protected abstract void updateFuture(CompletableFuture<? extends Either<? extends Chunk, ChunkHolder.Unloaded>> future);
}
