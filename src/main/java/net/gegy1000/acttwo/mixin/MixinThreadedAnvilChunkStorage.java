package net.gegy1000.acttwo.mixin;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.AsyncChunkState;
import net.gegy1000.acttwo.chunk.ChunkGenWorker;
import net.gegy1000.acttwo.chunk.ChunkHolderExt;
import net.gegy1000.acttwo.chunk.TacsExt;
import net.gegy1000.acttwo.chunk.future.GetChunkContext;
import net.gegy1000.acttwo.chunk.future.UnloadedChunk;
import net.gegy1000.acttwo.chunk.future.UpgradeChunk;
import net.gegy1000.acttwo.chunk.future.VanillaChunkFuture;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTaskPrioritySystem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.MessageListener;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class MixinThreadedAnvilChunkStorage implements TacsExt {
    @Shadow
    @Final
    private MessageListener<ChunkTaskPrioritySystem.Task<Runnable>> worldGenExecutor;

    @Shadow
    @Final
    private ChunkTaskPrioritySystem chunkTaskPrioritySystem;

    @Shadow
    protected abstract ChunkHolder getCurrentChunkHolder(long pos);

    @Shadow
    protected abstract CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> convertToFullChunk(ChunkHolder chunkHolder);

    @Shadow
    protected abstract CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> loadChunk(ChunkPos pos);

    private final ChunkGenWorker worker = new ChunkGenWorker();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(
            ServerWorld world,
            LevelStorage.Session session,
            DataFixer fixer,
            StructureManager structureManager,
            Executor workerExecutor,
            ThreadExecutor<Runnable> mainThreadExecutor,
            ChunkProvider chunkProvider,
            ChunkGenerator chunkGenerator,
            WorldGenerationProgressListener progressListener,
            Supplier<PersistentStateManager> persistentStateSupplier,
            int viewDistance,
            boolean bl,
            CallbackInfo ci
    ) {
        this.worldGenExecutor.close();
    }

    private ThreadedAnvilChunkStorage self() {
        return (ThreadedAnvilChunkStorage) (Object) this;
    }

    @Override
    public Future<Chunk> getChunk(int chunkX, int chunkZ, ChunkStatus targetStatus) {
        ChunkHolder holder = this.getCurrentChunkHolder(ChunkPos.toLong(chunkX, chunkZ));
        if (holder == null) {
            return UnloadedChunk.INSTANCE;
        }

        return this.getChunk(holder, targetStatus);
    }

    @Override
    public Future<Chunk> getChunk(ChunkHolder holder, ChunkStatus targetStatus) {
        AsyncChunkState asyncState = ((ChunkHolderExt) holder).getAsyncState();
        asyncState.upgradeTo(this.self(), targetStatus, this::spawnUpgradeFrom);

        return asyncState.getListenerFor(targetStatus);
    }

    @Override
    public void spawnUpgradeFrom(
            Future<Chunk> fromFuture, ChunkHolder holder,
            ChunkStatus fromStatus, ChunkStatus toStatus
    ) {
        if (fromStatus == toStatus) {
            return;
        }

        this.worker.spawn(fromFuture.andThen(chunk -> this.upgradeChunk(holder, fromStatus, toStatus)));
    }

    @Override
    public GetChunkContext getChunkContext(ChunkPos pos, ChunkStatus[] statuses) {
        return new GetChunkContext(this.self(), pos, statuses);
    }

    @Override
    public UpgradeChunk upgradeChunk(ChunkHolder holder, ChunkStatus currentStatus, ChunkStatus targetStatus) {
        return new UpgradeChunk(this.self(), holder, currentStatus, targetStatus);
    }

    @Override
    public Future<Chunk> spawnLoadChunk(ChunkHolder holder) {
        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future = this.loadChunk(holder.getPos())
                .handle((result, throwable) -> {
                    if (result != null) {
                        ChunkHolderExt holderExt = (ChunkHolderExt) holder;
                        holderExt.getAsyncState().complete(ChunkStatus.EMPTY, result);
                    }
                    return result;
                });

        return new VanillaChunkFuture(future);
    }

    @Override
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> accessConvertToFullChunk(ChunkHolder holder) {
        return this.convertToFullChunk(holder);
    }

    /**
     * @reason redirect to ChunkHolder implementation
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> createChunkFuture(ChunkHolder holder, ChunkStatus toStatus) {
        return holder.createFuture(toStatus, this.self());
    }
}
