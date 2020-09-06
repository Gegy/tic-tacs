package net.gegy1000.tictacs.mixin;

import com.google.common.collect.Iterables;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.fabricmc.fabric.api.util.NbtType;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.AsyncChunkIo;
import net.gegy1000.tictacs.AsyncRegionStorageIo;
import net.gegy1000.tictacs.VoidActor;
import net.gegy1000.tictacs.async.worker.ChunkMainThreadExecutor;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.ChunkMap;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.future.AwaitAll;
import net.gegy1000.tictacs.chunk.future.ChunkNotLoadedFuture;
import net.gegy1000.tictacs.chunk.future.LazyRunnableFuture;
import net.gegy1000.tictacs.chunk.future.VanillaChunkFuture;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.tictacs.chunk.tracker.ChunkTracker;
import net.gegy1000.tictacs.chunk.upgrade.ChunkUpgrader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Packet;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.PlayerChunkWatchingManager;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageMixin extends VersionedChunkStorage implements ChunkController, AsyncChunkIo {
    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private PointOfInterestStorage pointOfInterestStorage;
    @Shadow
    @Final
    private StructureManager structureManager;
    @Shadow
    @Final
    private Supplier<PersistentStateManager> persistentStateManagerFactory;
    @Shadow
    @Final
    private ServerWorld world;
    @Shadow
    @Final
    private ThreadExecutor<Runnable> mainThreadExecutor;
    @Shadow
    @Final
    private ServerLightingProvider serverLightingProvider;
    @Shadow
    @Final
    private ThreadedAnvilChunkStorage.TicketManager ticketManager;
    @Shadow
    @Final
    private WorldGenerationProgressListener worldGenerationProgressListener;
    @Shadow
    @Final
    private AtomicInteger totalChunksLoadedCount;
    @Shadow
    private int watchDistance;

    @Unique
    private ChunkMap map;
    @Unique
    private ChunkUpgrader upgrader;
    @Unique
    private ChunkTracker tracker;

    @Unique
    private ChunkLevelTracker levelTracker;

    @Unique
    private ChunkMainThreadExecutor chunkMainExecutor;

    private ThreadedAnvilChunkStorageMixin(File file, DataFixer fixer, boolean dsync) {
        super(file, fixer, dsync);
    }

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

        this.map = new ChunkMap(world, this);
        this.upgrader = new ChunkUpgrader(world, this, chunkGenerator, structures, lighting);

        this.levelTracker = new ChunkLevelTracker(world, this);

        this.tracker = new ChunkTracker(world, this);
        this.tracker.setViewDistance(this.watchDistance);

        this.chunkMainExecutor = new ChunkMainThreadExecutor(mainThread);
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
        return new VoidActor(name);
    }

    @Override
    public ChunkMap getMap() {
        return this.map;
    }

    @Override
    public ChunkUpgrader getUpgrader() {
        return this.upgrader;
    }

    @Override
    public ChunkTicketManager getTicketManager() {
        return this.ticketManager;
    }

    @Override
    public Future<Unit> getRadiusAs(ChunkPos pos, int radius, ChunkStep step) {
        ChunkAccess chunks = this.map.visible();

        ChunkMap.FlushListener flushListener = this.map.awaitFlush();

        int size = radius * 2 + 1;
        Future<Chunk>[] futures = new Future[size * size];
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * size;
                ChunkEntry entry = chunks.getEntry(pos.x + x, pos.z + z);
                if (entry == null) {
                    return flushListener.andThen(unit -> this.getRadiusAs(pos, radius, step));
                }

                if (entry.isValidAs(step)) {
                    this.upgrader.spawnUpgradeTo(entry, step);
                    futures[idx] = entry.getListenerFor(step);
                } else {
                    return ChunkNotLoadedFuture.get();
                }
            }
        }

        flushListener.invalidateWaker();

        return new AwaitAll<>(futures);
    }

    @Override
    public Future<Chunk> spawnLoadChunk(ChunkEntry entry) {
        return VanillaChunkFuture.of(this.loadChunk(entry.getPos()));
    }

    @Override
    public void notifyStatus(ChunkPos pos, ChunkStatus status) {
        this.worldGenerationProgressListener.setChunkStatus(pos, status);
    }

    @Override
    public <T> void spawnOnMainThread(ChunkEntry entry, Future<T> future) {
        this.chunkMainExecutor.spawn(entry, future);
    }

    @Override
    public void spawnOnMainThread(ChunkEntry entry, Runnable runnable) {
        this.chunkMainExecutor.spawn(entry, new LazyRunnableFuture(runnable));
    }

    /**
     * @reason delegate to ChunkMap
     * @author gegy1000
     */
    @Nullable
    @Overwrite
    public ChunkHolder getCurrentChunkHolder(long pos) {
        return this.map.primary().getEntry(pos);
    }

    /**
     * @reason delegate to ChunkMap
     * @author gegy1000
     */
    @Nullable
    @Overwrite
    public ChunkHolder getChunkHolder(long pos) {
        return this.map.visible().getEntry(pos);
    }

    /**
     * @reason replace usage of ChunkStatus and delegate to custom upgrader logic
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> getChunk(ChunkHolder holder, ChunkStatus status) {
        ChunkStep step = ChunkStep.byStatus(status);

        ChunkEntry entry = (ChunkEntry) holder;
        this.upgrader.spawnUpgradeTo(entry, step);

        return entry.getListenerFor(step).asVanilla();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    public void tick(BooleanSupplier runWhile, CallbackInfo ci) {
        this.map.flushToVisible();
    }

    @Redirect(
            method = "unloadChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;remove(J)Ljava/lang/Object;",
                    remap = false
            )
    )
    private Object removeChunkForUnload(Long2ObjectLinkedOpenHashMap<ChunkHolder> map, long pos) {
        return this.map.primary().removeEntry(pos);
    }

    @Redirect(
            method = "save(Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;",
                    remap = false
            )
    )
    private ObjectCollection<?> getChunks(Long2ObjectLinkedOpenHashMap<?> map) {
        return this.map.primary().getEntries();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void close(CallbackInfo ci) {
        this.chunkMainExecutor.close();
    }

    /**
     * @reason delegate to ChunkLevelTracker
     * @author gegy1000
     */
    @Nullable
    @Overwrite
    private ChunkHolder setLevel(long pos, int toLevel, @Nullable ChunkHolder entry, int fromLevel) {
        return this.levelTracker.setLevel(pos, toLevel, (ChunkEntry) entry, fromLevel);
    }

    /**
     * @reason replace the level used for light tickets
     * @author gegy1000
     */
    @Overwrite
    public void releaseLightTicket(ChunkPos pos) {
        this.mainThreadExecutor.send(() -> {
            this.ticketManager.removeTicketWithLevel(ChunkTicketType.LIGHT, pos, ChunkEntry.LIGHT_TICKET_LEVEL, pos);
        });
    }

    /**
     * @reason replace usage of async area-loading
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> makeChunkEntitiesTickable(ChunkPos pos) {
        CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> future = new CompletableFuture<>();

        ChunkEntry entry = this.map.primary().getEntry(pos);

        this.spawnOnMainThread(entry, this.getRadiusAs(pos, 2, ChunkStep.FULL).handle((ok, err) -> {
            if (err == null && entry.getWorldChunk() != null) {
                future.complete(Either.left(entry.getWorldChunk()));
            } else {
                future.complete(ChunkHolder.UNLOADED_WORLD_CHUNK);
            }
            return Unit.INSTANCE;
        }));

        return future;
    }

    /**
     * @reason replace usage of async area-loading
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> makeChunkTickable(ChunkHolder holder) {
        CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> future = new CompletableFuture<>();
        ChunkEntry entry = (ChunkEntry) holder;

        this.spawnOnMainThread(entry, this.getRadiusAs(entry.getPos(), 1, ChunkStep.FULL)
                .handle((ok, err) -> {
                    if (err != null) {
                        future.complete(ChunkHolder.UNLOADED_WORLD_CHUNK);
                        return Unit.INSTANCE;
                    }

                    WorldChunk chunk = entry.getWorldChunk();
                    if (chunk != null) {
                        chunk.runPostProcessing();

                        this.totalChunksLoadedCount.getAndIncrement();
                        this.tracker.onChunkFull(entry, chunk);

                        future.complete(Either.left(chunk));
                    } else {
                        future.complete(ChunkHolder.UNLOADED_WORLD_CHUNK);
                    }

                    return Unit.INSTANCE;
                })
        );

        return future;
    }

    /**
     * @reason delegate to ChunkMap
     * @author gegy1000
     */
    @Overwrite
    public boolean updateHolderMap() {
        return this.map.flushToVisible();
    }

    /**
     * @reason delegate to ChunkMap
     * @author gegy1000
     */
    @Overwrite
    public int getLoadedChunkCount() {
        return this.map.getEntryCount();
    }

    /**
     * @reason delegate to ChunkMap
     * @author gegy1000
     */
    @Overwrite
    public Iterable<ChunkHolder> entryIterator() {
        return Iterables.unmodifiableIterable(this.map.visible().getEntries());
    }

    @Redirect(
            method = "setViewDistance",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;",
                    remap = false
            )
    )
    private ObjectCollection<ChunkHolder> setViewDistance(Long2ObjectLinkedOpenHashMap<ChunkHolder> chunks) {
        if (this.tracker != null) {
            this.tracker.setViewDistance(this.watchDistance);
        }
        return ObjectLists.emptyList();
    }

    /**
     * @reason delegate to ChunkTracker
     * @author gegy1000
     */
    @Overwrite
    public void loadEntity(Entity entity) {
        if (entity instanceof EnderDragonPart) {
            return;
        }
        this.tracker.addEntity(entity);
    }

    /**
     * @reason delegate to ChunkTracker
     * @author gegy1000
     */
    @Overwrite
    public void unloadEntity(Entity entity) {
        this.tracker.removeEntity(entity);
    }

    /**
     * @reason delegate to ChunkTracker
     * @author gegy1000
     */
    @Overwrite
    public void sendToOtherNearbyPlayers(Entity entity, Packet<?> packet) {
        this.tracker.sendToTracking(entity, packet);
    }

    /**
     * @reason delegate to ChunkTracker
     * @author gegy1000
     */
    @Overwrite
    public void sendToNearbyPlayers(Entity entity, Packet<?> packet) {
        this.tracker.sendToTrackingAndSelf(entity, packet);
    }

    /**
     * @reason use cached list of tracking players on the chunk entry
     * @author gegy1000
     */
    @Overwrite
    public boolean isTooFarFromPlayersToSpawnMobs(ChunkPos chunkPos) {
        long pos = chunkPos.toLong();

        ChunkEntry entry = this.map.visible().getEntry(pos);
        return entry == null || this.isTooFarFromPlayersToSpawnMobs(entry);
    }

    @Override
    public boolean isTooFarFromPlayersToSpawnMobs(ChunkEntry entry) {
        for (ServerPlayerEntity player : entry.getTrackingPlayers()) {
            if (!player.isSpectator() && getSquaredDistance(entry.getPos(), player) < 128 * 128) {
                return !this.ticketManager.method_20800(entry.getPos().toLong());
            }
        }

        return true;
    }

    /**
     * @reason use cached list of tracking players on the chunk entry
     * @author gegy1000
     */
    @Redirect(
            method = "getPlayersWatchingChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/PlayerChunkWatchingManager;getPlayersWatchingChunk(J)Ljava/util/stream/Stream;"
            )
    )
    private Stream<ServerPlayerEntity> getPlayersWatchingChunk(PlayerChunkWatchingManager watchManager, long pos) {
        return this.getPlayersWatchingChunk(pos);
    }

    private Stream<ServerPlayerEntity> getPlayersWatchingChunk(long pos) {
        ChunkEntry entry = this.map.visible().getEntry(pos);
        if (entry != null) {
            return entry.getTrackingPlayers().stream();
        }
        return Stream.empty();
    }

    /**
     * @reason delegate to ChunkTracker
     * @author gegy1000
     */
    @Overwrite
    public void tickPlayerMovement() {
        this.tracker.tick();
    }

    /**
     * @reason we already detect player movement across chunks through normal entity tracker handling
     * @author gegy1000
     */
    @Inject(method = "updateCameraPosition", at = @At("HEAD"), cancellable = true)
    private void updateCameraPosition(ServerPlayerEntity player, CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * @reason avoid blocking the main thread to load chunk nbt from disk
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> loadChunk(ChunkPos pos) {
        return this.getUpdatedChunkTagAsync(pos)
                .handleAsync((tag, throwable) -> {
                    if (throwable != null) {
                        if (throwable instanceof CompletionException) {
                            throwable = throwable.getCause();
                        }
                        LOGGER.error("Couldn't load chunk {}", pos, throwable);
                    }

                    return this.loadChunkFromTag(pos, tag);
                }, this.mainThreadExecutor);
    }

    private Either<Chunk, ChunkHolder.Unloaded> loadChunkFromTag(ChunkPos pos, CompoundTag tag) {
        try {
            this.world.getProfiler().visit("chunkLoad");
            if (tag != null) {
                Chunk chunk = this.deserializeChunk(pos, tag);
                if (chunk != null) {
                    return Either.left(chunk);
                } else {
                    LOGGER.error("Chunk file at {} is missing level data, skipping", pos);
                }
            }
        } catch (CrashException crash) {
            Throwable cause = crash.getCause();
            if (!(cause instanceof IOException)) {
                this.method_27054(pos);
                throw crash;
            }

            LOGGER.error("Couldn't load chunk {}", pos, crash);
        } catch (Exception e) {
            LOGGER.error("Couldn't load chunk {}", pos, e);
        }

        this.method_27054(pos);
        return Either.left(new ProtoChunk(pos, UpgradeData.NO_UPGRADE_DATA));
    }

    @Nullable
    private Chunk deserializeChunk(ChunkPos pos, CompoundTag tag) {
        if (!tag.contains("Level", NbtType.COMPOUND) || !tag.getCompound("Level").contains("Status", NbtType.STRING)) {
            return null;
        }

        Chunk chunk = ChunkSerializer.deserialize(this.world, this.structureManager, this.pointOfInterestStorage, pos, tag);
        chunk.setLastSaveTime(this.world.getTime());
        this.method_27053(pos, chunk.getStatus().getChunkType());

        return chunk;
    }

    private CompletableFuture<CompoundTag> getUpdatedChunkTagAsync(ChunkPos pos) {
        CompletableFuture<CompoundTag> chunkTag = this.getNbtAsync(pos).thenApplyAsync(tag -> {
            if (tag == null) {
                return null;
            }

            return this.updateChunkTag(this.world.getRegistryKey(), this.persistentStateManagerFactory, tag);
        }, this.mainThreadExecutor);

        CompletableFuture<Void> loadPoi = ((AsyncRegionStorageIo) this.pointOfInterestStorage).loadDataAtAsync(pos, this.mainThreadExecutor);

        return chunkTag.thenCombine(loadPoi, (tag, v) -> tag);
    }

    @Shadow
    protected abstract void method_27054(ChunkPos chunkPos);

    @Shadow
    protected abstract byte method_27053(ChunkPos chunkPos, ChunkStatus.ChunkType chunkType);

    @Shadow
    private static double getSquaredDistance(ChunkPos pos, Entity entity) {
        throw new UnsupportedOperationException();
    }
}
