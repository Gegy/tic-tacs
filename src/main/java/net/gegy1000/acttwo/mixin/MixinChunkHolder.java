package net.gegy1000.acttwo.mixin;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.ChunkHolderExt;
import net.gegy1000.acttwo.chunk.ChunkListener;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.TacsExt;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

// TODO: issues
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
    private static CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> UNLOADED_WORLD_CHUNK_FUTURE;

    @Shadow
    @Final
    private ChunkPos pos;

    @Shadow
    private int level;

    @Shadow
    private int lastTickLevel;

    @Shadow
    private int completedLevel;

    // accessible
    @Shadow
    private boolean ticking;

    @Shadow
    private volatile CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> entityTickingFuture;

    @Shadow
    private volatile CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> tickingFuture;

    // accessibleFuture
    @Shadow
    private volatile CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> borderFuture;

    @Shadow
    @Final
    @Mutable
    private AtomicReferenceArray<CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>>> futuresByStatus;

    private final ChunkListener[] listeners = new ChunkListener[STATUSES.size()];

    private final AtomicReference<ChunkStatus> targetStatus = new AtomicReference<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(
            ChunkPos pos, int level,
            LightingProvider lighting,
            ChunkHolder.LevelUpdateListener levelUpdateListener,
            ChunkHolder.PlayersWatchingChunkProvider watching,
            CallbackInfo ci
    ) {
        for (ChunkStatus status : STATUSES) {
            int index = status.getIndex();
            this.listeners[index] = new ChunkListener();
            this.futuresByStatus.set(index, this.listeners[index].completable);
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
    public void tryUpgradeTo(ThreadedAnvilChunkStorage tacs, ChunkStatus toStatus) {
        if (!this.shouldGenerateUpTo(toStatus)) {
            return;
        }

        TacsExt tacsExt = (TacsExt) tacs;

        while (true) {
            ChunkStatus fromStatus = this.targetStatus.get();
            if (fromStatus != null && fromStatus.isAtLeast(toStatus)) {
                return;
            }

            if (this.targetStatus.compareAndSet(fromStatus, toStatus)) {
                this.spawnUpgrade(tacsExt, fromStatus, toStatus);

                ChunkListener listener = this.getListenerFor(toStatus);
                this.updateFuture(listener.completable);

                break;
            }
        }
    }

    private void spawnUpgrade(TacsExt tacs, ChunkStatus fromStatus, ChunkStatus toStatus) {
        if (fromStatus == null) {
            tacs.spawnLoadChunk(this.self());
            fromStatus = ChunkStatus.EMPTY;
        }

        ChunkListener fromFuture = this.getListenerFor(fromStatus);
        tacs.spawnUpgradeFrom(fromFuture, this.self(), fromStatus, toStatus);
    }

    @Override
    public ChunkListener getListenerFor(ChunkStatus status) {
        return this.listeners[status.getIndex()];
    }

    private boolean shouldGenerateUpTo(ChunkStatus status) {
        return ChunkHolder.getTargetGenerationStatus(this.level).isAtLeast(status);
    }

    @Override
    public void complete(ChunkStatus status, Either<Chunk, ChunkHolder.Unloaded> result) {
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

    /**
     * @reason rewrite all async future logic
     * @author gegy1000
     */
    @Overwrite
    public void tick(ThreadedAnvilChunkStorage tacs) {
        if (this.level > this.lastTickLevel) {
            this.reduceLevel(this.lastTickLevel, this.level);
        }

        ChunkHolder.LevelType lastLevelType = ChunkHolder.getLevelType(this.lastTickLevel);
        ChunkHolder.LevelType currentLevelType = ChunkHolder.getLevelType(this.level);

        boolean wasAccessible = lastLevelType.isAfter(ChunkHolder.LevelType.BORDER);
        boolean isAccessible = currentLevelType.isAfter(ChunkHolder.LevelType.BORDER);
        this.ticking |= isAccessible;

        if (isAccessible != wasAccessible) {
            this.updateAccessible(tacs, isAccessible);
        }

        boolean wasTicking = lastLevelType.isAfter(ChunkHolder.LevelType.TICKING);
        boolean isTicking = currentLevelType.isAfter(ChunkHolder.LevelType.TICKING);
        if (isTicking != wasTicking) {
            this.updateTicking(tacs, isTicking);
        }

        boolean wasTickingEntities = lastLevelType.isAfter(ChunkHolder.LevelType.ENTITY_TICKING);
        boolean isTickingEntities = currentLevelType.isAfter(ChunkHolder.LevelType.ENTITY_TICKING);
        if (isTickingEntities != wasTickingEntities) {
            this.updateEntityTicking(tacs, isTickingEntities);
        }

        this.completedLevel = this.level;
        this.lastTickLevel = this.level;
    }

    private void updateAccessible(ThreadedAnvilChunkStorage tacs, boolean isAccessible) {
        if (isAccessible) {
            this.borderFuture = tacs.createBorderFuture(this.self());
            this.updateFuture(this.borderFuture);
        } else {
            CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> accessibleFuture = this.borderFuture;
            this.borderFuture = UNLOADED_WORLD_CHUNK_FUTURE;
            this.updateFuture(accessibleFuture.thenApply(result -> result.ifLeft(tacs::method_20576)));
        }
    }

    private void updateTicking(ThreadedAnvilChunkStorage tacs, boolean ticking) {
        if (ticking) {
            this.tickingFuture = tacs.createTickingFuture(this.self());
            this.updateFuture(this.tickingFuture);
        } else {
            this.tickingFuture.complete(ChunkHolder.UNLOADED_WORLD_CHUNK);
            this.tickingFuture = UNLOADED_WORLD_CHUNK_FUTURE;
        }
    }

    private void updateEntityTicking(ThreadedAnvilChunkStorage tacs, boolean ticking) {
        if (ticking) {
            if (this.entityTickingFuture != UNLOADED_WORLD_CHUNK_FUTURE) {
                throw new IllegalStateException();
            }

            this.entityTickingFuture = tacs.createEntityTickingChunkFuture(this.pos);
            this.updateFuture(this.entityTickingFuture);
        } else {
            this.entityTickingFuture.complete(ChunkHolder.UNLOADED_WORLD_CHUNK);
            this.entityTickingFuture = UNLOADED_WORLD_CHUNK_FUTURE;
        }
    }

    private void reduceLevel(int lastLevel, int level) {
        boolean wasLoaded = this.lastTickLevel <= ThreadedAnvilChunkStorage.MAX_LEVEL;
        boolean isLoaded = level <= ThreadedAnvilChunkStorage.MAX_LEVEL;

        if (!wasLoaded) {
            return;
        }

        ChunkStatus lastStatus = ChunkHolder.getTargetGenerationStatus(lastLevel);
        ChunkStatus currentStatus = ChunkHolder.getTargetGenerationStatus(level);

        int startIdx = isLoaded ? currentStatus.getIndex() + 1 : 0;
        int endIdx = lastStatus.getIndex();

        if (startIdx > endIdx) {
            return;
        }

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
