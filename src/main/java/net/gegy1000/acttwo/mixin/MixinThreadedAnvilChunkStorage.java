package net.gegy1000.acttwo.mixin;

import com.google.common.collect.Iterables;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.VoidActor;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.TacsExt;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class MixinThreadedAnvilChunkStorage implements TacsExt {
    @Shadow
    @Final
    private ServerLightingProvider serverLightingProvider;

    private ChunkController controller;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(
            ServerWorld world,
            LevelStorage.Session levelSession,
            DataFixer dataFixer,
            StructureManager structures,
            Executor threadPool,
            ThreadExecutor<Runnable> mainThread,
            ChunkProvider chunkProvider,
            ChunkGenerator chunkGenerator,
            WorldGenerationProgressListener progressListener,
            Supplier<PersistentStateManager> persistentStateSupplier,
            int watchDistance,
            boolean syncWrite,
            CallbackInfo ci
    ) {
        ServerLightingProvider lighting = this.serverLightingProvider;
        this.controller = new ChunkController(
                world, chunkGenerator, structures, lighting,
                levelSession, progressListener, threadPool, mainThread
        );
        this.controller.tracker.setWatchDistance(watchDistance);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/thread/TaskExecutor;create(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/TaskExecutor;",
                    ordinal = 0
            )
    )
    private TaskExecutor<Runnable> createWorldgenActor(Executor executor, String name) {
        return VoidActor.INSTANCE;
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Nullable
    @Overwrite
    public ChunkHolder getCurrentChunkHolder(long pos) {
        return this.controller.map.primary().getEntry(pos);
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Nullable
    @Overwrite
    public ChunkHolder getChunkHolder(long pos) {
        return this.controller.map.visible().getEntry(pos);
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> createChunkFuture(ChunkHolder holder, ChunkStatus status) {
        ChunkEntry entry = (ChunkEntry) holder;
        this.controller.upgrader.spawnUpgradeTo(entry, status);
        return entry.getListenerFor(status).asVanilla();
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> createTickingFuture(ChunkHolder holder) {
        ChunkEntry entry = (ChunkEntry) holder;
        return this.awaitToVanilla(entry, entry.awaitTickable());
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> createBorderFuture(ChunkHolder holder) {
        ChunkEntry entry = (ChunkEntry) holder;
        return this.awaitToVanilla(entry, entry.awaitAccessible());
    }

    private CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> awaitToVanilla(ChunkEntry entry, Future<Unit> future) {
        CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> completable = new CompletableFuture<>();

        this.controller.spawnOnMainThread(entry, future.map(unit -> {
            WorldChunk chunk = entry.getWorldChunk();
            if (chunk != null) {
                completable.complete(Either.left(chunk));
            } else {
                completable.complete(ChunkHolder.UNLOADED_WORLD_CHUNK);
            }
            return unit;
        }));

        return completable;
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public void tick(BooleanSupplier runWhile) {
        this.controller.tick(runWhile);
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public boolean updateHolderMap() {
        return this.controller.map.flushToVisible();
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public void setViewDistance(int watchDistance) {
        // called from constructor
        if (this.controller == null) return;

        this.controller.tracker.setWatchDistance(watchDistance);
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public int getTotalChunksLoadedCount() {
        return this.controller.map.getTickingChunksLoaded();
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public int getLoadedChunkCount() {
        return this.controller.map.getEntryCount();
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public Iterable<ChunkHolder> entryIterator() {
        return Iterables.unmodifiableIterable(this.controller.map.visible().getEntries());
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public void save(boolean flush) {
        this.controller.saveAll(flush);
    }

    /**
     * @reason delegate to controller
     * @author gegy1000
     */
    @Overwrite
    public void close() throws IOException {
        this.controller.close();
    }

    @Override
    public ChunkController getController() {
        return this.controller;
    }
}
