package net.gegy1000.tictacs.mixin;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.AsyncChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.LossyChunkCache;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.BlockView;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerMixin implements AsyncChunkAccess {
    @Shadow
    @Final
    private ServerWorld world;
    @Shadow
    @Final
    private ChunkTicketManager ticketManager;
    @Shadow
    @Final
    private ServerChunkManager.MainThreadExecutor mainThreadExecutor;
    @Shadow
    @Final
    public ThreadedAnvilChunkStorage threadedAnvilChunkStorage;

    @Shadow
    @Final
    private Thread serverThread;

    @Unique
    private final LossyChunkCache fastCache = new LossyChunkCache(32);

    @Inject(method = "initChunkCaches", at = @At("HEAD"))
    private void clearChunkCache(CallbackInfo ci) {
        this.fastCache.clear();
    }

    /**
     * @reason optimize chunk query and cache logic and avoid blocking the main thread if possible
     * @author gegy1000
     */
    @Overwrite
    @Nullable
    public Chunk getChunk(int x, int z, ChunkStatus status, boolean create) {
        ChunkStep step = ChunkStep.byStatus(status);
        if (create) {
            if (Thread.currentThread() != this.serverThread) {
                return this.getOrCreateChunkOffThread(x, z, step);
            } else {
                return this.getOrCreateChunkOnThread(x, z, step);
            }
        } else {
            return this.getExistingChunk(x, z, step);
        }
    }

    private Chunk getOrCreateChunkOnThread(int x, int z, ChunkStep step) {
        // first we test if the chunk already exists in our small cache
        Chunk cached = this.fastCache.get(x, z, step);
        if (cached != null) {
            return cached;
        }

        // if it does not exist, try load it from the chunk entry
        ChunkEntry entry = this.getChunkEntry(x, z);
        Chunk chunk = this.getExistingChunkFor(entry, step);

        // the chunk is not ready, we must interface and join the chunk future
        if (chunk == null) {
            Either<Chunk, ChunkHolder.Unloaded> result = this.joinFuture(this.createChunk(x, z, step));

            chunk = result.map(
                    Function.identity(),
                    err -> { throw new IllegalStateException("Chunk not there when requested: " + err); }
            );
        }

        this.fastCache.put(x, z, step, chunk);

        return chunk;
    }

    private <T> T joinFuture(CompletableFuture<T> future) {
        if (!future.isDone()) {
            this.mainThreadExecutor.runTasks(future::isDone);
        }
        return future.join();
    }

    private Chunk getOrCreateChunkOffThread(int x, int z, ChunkStep step) {
        Either<Chunk, ChunkHolder.Unloaded> result = CompletableFuture.supplyAsync(
                () -> this.createChunk(x, z, step),
                this.mainThreadExecutor
        ).join().join();

        return result.map(
                chunk -> chunk,
                unloaded -> {
                    throw new IllegalStateException("Chunk not there when requested: " + unloaded);
                }
        );
    }

    /**
     * @reason optimize chunk query and cache logic and avoid blocking the main thread if possible
     * @author gegy1000
     */
    @Overwrite
    @Nullable
    public WorldChunk getWorldChunk(int x, int z) {
        return (WorldChunk) this.getExistingChunk(x, z, ChunkStep.FULL);
    }

    /**
     * @reason optimize chunk query
     * @author gegy1000
     */
    @Overwrite
    @Nullable
    public BlockView getChunk(int x, int z) {
        ChunkEntry entry = this.getChunkEntry(x, z);
        if (entry != null) {
            return entry.getChunkAtLeast(ChunkStep.FEATURES);
        }
        return null;
    }

    @Override
    public Chunk getExistingChunk(int x, int z, ChunkStep step) {
        if (Thread.currentThread() != this.serverThread) {
            return this.loadExistingChunk(x, z, step);
        }

        Chunk cached = this.fastCache.get(x, z, step);
        if (cached != null) {
            return cached;
        }

        Chunk chunk = this.loadExistingChunk(x, z, step);
        this.fastCache.put(x, z, step, chunk);

        return chunk;
    }

    @Override
    public Chunk getAnyExistingChunk(int x, int z) {
        ChunkEntry entry = this.getChunkEntry(x, z);
        if (entry != null) {
            return entry.getChunk();
        }
        return null;
    }

    @Nullable
    private Chunk loadExistingChunk(int x, int z, ChunkStep step) {
        ChunkEntry entry = this.getChunkEntry(x, z);
        return this.getExistingChunkFor(entry, step);
    }

    @Nullable
    private Chunk getExistingChunkFor(@Nullable ChunkEntry entry, ChunkStep step) {
        if (entry != null && entry.isValidAs(step)) {
            return entry.getChunkForStep(step);
        }
        return null;
    }

    /**
     * @reason replace with implementation that will not return true for partially loaded chunks
     * @author gegy1000
     */
    @Overwrite
    public boolean isChunkLoaded(int x, int z) {
        return this.getExistingChunk(x, z, ChunkStep.FULL) != null;
    }

    @Override
    public CompletableFuture<Chunk> getOrCreateChunkAsync(int x, int z, ChunkStep step) {
        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future;

        if (Thread.currentThread() != this.serverThread) {
            future = CompletableFuture.supplyAsync(() -> this.createChunk(x, z, step), this.mainThreadExecutor)
                    .thenCompose(Function.identity());
        } else {
            future = this.createChunk(x, z, step);
        }

        return future.thenApply(result -> result.map(
                chunk -> chunk,
                unloaded -> {
                    throw new IllegalStateException("Chunk not there when requested: " + unloaded);
                })
        );
    }

    private CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> createChunk(int x, int z, ChunkStep step) {
        ChunkEntry entry = this.getChunkEntry(x, z);

        ChunkPos pos = new ChunkPos(x, z);
        int level = getLevelForStep(step);
        this.ticketManager.addTicketWithLevel(ChunkTicketType.UNKNOWN, pos, level, pos);

        while (entry == null || !entry.isValidAs(step)) {
            Profiler profiler = this.world.getProfiler();
            profiler.push("chunkLoad");

            // tick the ticket manager to propagate any changes and reacquire the chunk entry
            this.tick();
            entry = this.getChunkEntry(x, z);

            profiler.pop();

            if (entry == null || !entry.isValidAs(step)) {
                throw new IllegalStateException("No chunk entry after ticket has been added");
            }
        }

        ChunkController controller = (ChunkController) this.threadedAnvilChunkStorage;
        return controller.getChunkAs(entry, step).asVanilla();
    }

    @Override
    public boolean shouldChunkExist(int x, int z, ChunkStep step) {
        ChunkEntry entry = this.getChunkEntry(x, z);
        return entry != null && entry.getLevel() <= getLevelForStep(step);
    }

    private static int getLevelForStep(ChunkStep step) {
        return ChunkLevelTracker.FULL_LEVEL + ChunkStep.getDistanceFromFull(step);
    }

    @Nullable
    private ChunkEntry getChunkEntry(int x, int z) {
        return (ChunkEntry) this.getChunkHolder(ChunkPos.toLong(x, z));
    }

    @Nullable
    private ChunkEntry getChunkEntry(long pos) {
        return (ChunkEntry) this.getChunkHolder(pos);
    }

    /**
     * @reason direct logic to {@link ChunkEntry} and avoid allocation
     * @author gegy1000
     */
    @Overwrite
    public boolean shouldTickEntity(Entity entity) {
        ChunkEntry entry = this.getChunkEntry(MathHelper.floor(entity.getX()) >> 4, MathHelper.floor(entity.getZ()) >> 4);
        return entry != null && entry.isTickingEntities();
    }

    /**
     * @reason direct logic to {@link ChunkEntry} and avoid allocation
     * @author gegy1000
     */
    @Overwrite
    public boolean shouldTickChunk(ChunkPos pos) {
        ChunkEntry entry = this.getChunkEntry(pos.toLong());
        return entry != null && entry.isTickingEntities();
    }

    /**
     * @reason direct logic to {@link ChunkEntry} and avoid allocation
     * @author gegy1000
     */
    @Overwrite
    public boolean shouldTickBlock(BlockPos pos) {
        ChunkEntry entry = this.getChunkEntry(pos.getX() >> 4, pos.getZ() >> 4);
        return entry != null && entry.isTicking();
    }

    @Shadow
    protected abstract boolean tick();

    @Shadow
    @Nullable
    protected abstract ChunkHolder getChunkHolder(long pos);
}
