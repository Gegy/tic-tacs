package net.gegy1000.tictacs.mixin;

import com.google.common.collect.Iterables;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.VoidActor;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.ChunkMap;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.future.AwaitAll;
import net.gegy1000.tictacs.chunk.future.LazyRunnableFuture;
import net.gegy1000.tictacs.chunk.future.VanillaChunkFuture;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.tictacs.chunk.upgrade.ChunkUpgrader;
import net.gegy1000.tictacs.chunk.worker.ChunkMainThreadExecutor;
import net.minecraft.network.Packet;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.math.ChunkPos;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class MixinThreadedAnvilChunkStorage implements ChunkController {
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

    @Unique
    private ChunkMap map;
    @Unique
    private ChunkUpgrader upgrader;

    @Unique
    private ChunkLevelTracker levelTracker;

    @Unique
    private ChunkMainThreadExecutor chunkMainExecutor;

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/ChunkStatus;getMaxTargetGenerationRadius()I"))
    private static int getMaxTargetGenerationRadius() {
        return ChunkStep.getMaxDistance() + 1;
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
        return VoidActor.INSTANCE;
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
    public ChunkLevelTracker getLevelTracker() {
        return this.levelTracker;
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

                this.upgrader.spawnUpgradeTo(entry, step);
                futures[idx] = entry.getListenerFor(step);
            }
        }

        flushListener.invalidate();

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
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> createChunkFuture(ChunkHolder holder, ChunkStatus status) {
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
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;remove(J)Ljava/lang/Object;"
            )
    )
    private Object removeChunkForUnload(Long2ObjectLinkedOpenHashMap<ChunkHolder> map, long pos) {
        return this.map.primary().removeEntry(pos);
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
    public CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> createEntityTickingChunkFuture(ChunkPos pos) {
        CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> future = new CompletableFuture<>();

        ChunkEntry entry = this.map.primary().getEntry(pos);

        this.spawnOnMainThread(entry, this.getRadiusAs(pos, 2, ChunkStep.FULL).map(u -> {
            WorldChunk chunk = entry.getWorldChunk();
            if (chunk != null) {
                future.complete(Either.left(chunk));
            } else {
                future.complete(Either.right(ChunkHolder.Unloaded.INSTANCE));
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
    public CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> createTickingFuture(ChunkHolder holder) {
        CompletableFuture<Either<WorldChunk, ChunkHolder.Unloaded>> future = new CompletableFuture<>();

        this.spawnOnMainThread((ChunkEntry) holder, this.getRadiusAs(holder.getPos(), 1, ChunkStep.FULL)
                .map(u -> {
                    WorldChunk chunk = holder.getWorldChunk();
                    if (chunk == null) {
                        future.complete(Either.right(ChunkHolder.Unloaded.INSTANCE));
                        return Unit.INSTANCE;
                    }

                    chunk.runPostProcessing();
                    this.totalChunksLoadedCount.getAndIncrement();

                    Packet<?>[] packets = new Packet[2];
                    this.getPlayersWatchingChunk(holder.getPos(), false).forEach(player -> {
                        this.sendChunkDataPackets(player, packets, chunk);
                    });

                    future.complete(Either.left(chunk));

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

    @Shadow
    protected abstract CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> loadChunk(ChunkPos pos);

    @Shadow
    protected abstract void sendChunkDataPackets(ServerPlayerEntity player, Packet<?>[] packets, WorldChunk chunk);

    @Shadow
    public abstract Stream<ServerPlayerEntity> getPlayersWatchingChunk(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge);
}
