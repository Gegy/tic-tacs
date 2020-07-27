package net.gegy1000.tictacs.mixin;

import net.gegy1000.justnow.executor.CurrentThreadExecutor;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.async.worker.ChunkTask;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.entry.ChunkListener;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

@Mixin(ServerChunkManager.class)
public abstract class MixinServerChunkManager {
    @Shadow
    @Final
    public ThreadedAnvilChunkStorage threadedAnvilChunkStorage;
    @Shadow
    @Final
    private ChunkTicketManager ticketManager;
    @Shadow
    @Final
    private Thread serverThread;
    @Shadow
    @Final
    private ServerChunkManager.MainThreadExecutor mainThreadExecutor;
    @Shadow
    @Final
    private long[] chunkPosCache;
    @Shadow
    @Final
    private Chunk[] chunkCache;
    @Shadow
    @Final
    private ChunkStatus[] chunkStatusCache;
    @Shadow
    @Final
    private ServerWorld world;

    // TODO: incompatible with lithium

    /**
     * @reason replace with implementation that steals tasks onto the main thread executor to minimise blocking time
     * @author gegy1000
     */
    @Overwrite
    @Nullable
    public Chunk getChunk(int x, int z, ChunkStatus leastStatus, boolean create) {
        if (Thread.currentThread() != this.serverThread) {
            return CompletableFuture.supplyAsync(() -> this.getChunk(x, z, leastStatus, create), this.mainThreadExecutor).join();
        }

        Profiler profiler = this.world.getProfiler();
        profiler.visit("getChunk");

        // try load this chunk from the cache
        long pos = ChunkPos.toLong(x, z);
        for (int i = 0; i < 4; i++) {
            if (pos == this.chunkPosCache[i] && leastStatus == this.chunkStatusCache[i]) {
                Chunk chunk = this.chunkCache[i];
                if (chunk != null || !create) {
                    return chunk;
                }
            }
        }

        profiler.visit("getChunkCacheMiss");

        Chunk chunk = this.getChunkCacheMiss(pos, leastStatus, create);
        this.putInCache(pos, chunk, leastStatus);

        return chunk;
    }

    private Chunk getChunkCacheMiss(long pos, ChunkStatus status, boolean create) {
        ChunkController controller = (ChunkController) this.threadedAnvilChunkStorage;

        ChunkEntry entry = this.getEntryAs(controller, pos, status, create);
        if (entry == null) {
            return null;
        }

        // spawn the chunk upgrade to the target step if it is not already spawned
        ChunkStep targetStep = ChunkStep.byStatus(status);
        ChunkTask<Unit> task = controller.getUpgrader().spawnUpgradeTo(entry, targetStep);

        // if this chunk is still upgrading
        if (task != null) {
            // move this task to execute on the main thread to minimise blocking for the worker threads
            task.moveTo(controller.getMainThreadExecutor());

            // block until the task is complete
            this.mainThreadExecutor.runTasks(task::isComplete);
        }

        try {
            ChunkListener listener = entry.getListenerFor(targetStep);
            return CurrentThreadExecutor.blockOn(listener);
        } catch (ChunkNotLoadedException e) {
            if (create) {
                int x = ChunkPos.getPackedX(pos);
                int z = ChunkPos.getPackedZ(pos);
                throw new IllegalStateException("Chunk not there when requested: [" + x + "; " + z + "]");
            } else {
                return null;
            }
        }
    }

    @Nullable
    private ChunkEntry getEntryAs(ChunkController controller, long key, ChunkStatus status, boolean create) {
        int level = levelFor(status);

        ChunkEntry entry = this.getEntry(controller, key);
        if (create) {
            // add ticket to make sure this chunk is loaded
            ChunkPos pos = new ChunkPos(key);
            this.ticketManager.addTicketWithLevel(ChunkTicketType.field_14032, pos, level, pos);

            if (this.isMissingForLevel(entry, level)) {
                Profiler profiler = this.world.getProfiler();
                profiler.push("chunkLoad");

                // tick so that this chunk entry is created
                this.tick();
                entry = this.getEntry(controller, key);

                profiler.pop();

                if (this.isMissingForLevel(entry, level)) {
                    throw new IllegalStateException("No chunk holder after ticket has been added");
                }
            }
        } else {
            if (this.isMissingForLevel(entry, levelFor(status))) {
                return null;
            }
        }

        return entry;
    }

    @Nullable
    private ChunkEntry getEntry(ChunkController controller, long key) {
        return controller.getMap().primary().getEntry(key);
    }

    private static int levelFor(ChunkStatus status) {
        return ChunkEntry.FULL_LEVEL + ChunkStatus.getTargetGenerationRadius(status);
    }

    @Shadow
    protected abstract void putInCache(long pos, Chunk chunk, ChunkStatus status);

    @Shadow
    protected abstract boolean isMissingForLevel(@Nullable ChunkHolder holder, int maxLevel);

    @Shadow
    protected abstract boolean tick();
}
