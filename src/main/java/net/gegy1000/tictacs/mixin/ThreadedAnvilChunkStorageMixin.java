package net.gegy1000.tictacs.mixin;

import com.google.common.collect.Iterables;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.ChunkMap;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.tracker.ChunkTracker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.network.Packet;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.PlayerChunkWatchingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageMixin implements ChunkController {
    @Shadow
    @Final
    private ThreadExecutor<Runnable> mainThreadExecutor;
    @Shadow
    @Final
    private ThreadedAnvilChunkStorage.TicketManager ticketManager;
    @Shadow
    private int watchDistance;

    @Unique
    private ChunkMap map;
    @Unique
    private ChunkTracker tracker;

    @Unique
    private ChunkLevelTracker levelTracker;

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
        this.map = new ChunkMap(world, this);

        this.levelTracker = new ChunkLevelTracker(this);

        this.tracker = new ChunkTracker(world, this);
        this.tracker.setViewDistance(this.watchDistance);

        this.map.addListener(this.tracker);
    }

    @Override
    public ChunkMap getMap() {
        return this.map;
    }

    @Override
    public ChunkTicketManager getTicketManager() {
        return this.ticketManager;
    }

    @Override
    public ChunkTracker getTracker() {
        return this.tracker;
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
            this.ticketManager.removeTicketWithLevel(ChunkTicketType.LIGHT, pos, ChunkLevelTracker.LIGHT_TICKET_LEVEL, pos);
        });
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
        this.tracker.getEntities().add(entity);
    }

    /**
     * @reason delegate to ChunkTracker
     * @author gegy1000
     */
    @Overwrite
    public void unloadEntity(Entity entity) {
        this.tracker.getEntities().remove(entity);
    }

    /**
     * @reason delegate to ChunkTracker
     * @author gegy1000
     */
    @Overwrite
    public void sendToOtherNearbyPlayers(Entity entity, Packet<?> packet) {
        this.tracker.getEntities().sendToTracking(entity, packet);
    }

    /**
     * @reason delegate to ChunkTracker
     * @author gegy1000
     */
    @Overwrite
    public void sendToNearbyPlayers(Entity entity, Packet<?> packet) {
        this.tracker.getEntities().sendToTrackingAndSelf(entity, packet);
    }

    /**
     * @reason use cached list of tracking players on the chunk entry
     * @author gegy1000
     */
    @Overwrite
    public boolean isTooFarFromPlayersToSpawnMobs(ChunkPos chunkPos) {
        long pos = chunkPos.toLong();

        ChunkEntry entry = this.map.visible().getEntry(pos);
        return entry == null || !entry.isChunkTickable();
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
            return entry.getTrackers().getTrackingPlayers().stream();
        }
        return Stream.empty();
    }

    @Inject(
            method = "method_18193",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/atomic/AtomicInteger;getAndIncrement()I",
                    remap = false,
                    shift = At.Shift.AFTER
            ),
            cancellable = true,
            remap = false
    )
    private void onChunkTickable(ChunkPos pos, WorldChunk chunk, CallbackInfoReturnable<Either<Chunk, ChunkHolder.Unloaded>> ci) {
        ChunkEntry entry = this.map.primary().getEntry(pos.toLong());
        if (entry != null) {
            this.map.getTickingMaps().addTrackableChunk(entry);
            this.tracker.onChunkFull(entry, chunk);
        }

        ci.setReturnValue(Either.left(chunk));
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
}
