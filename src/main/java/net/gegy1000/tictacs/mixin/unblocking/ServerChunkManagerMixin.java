package net.gegy1000.tictacs.mixin.unblocking;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.AsyncChunkAccess;
import net.gegy1000.tictacs.chunk.LossyChunkCache;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.entry.ChunkListener;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerMixin implements AsyncChunkAccess {
    @Shadow
    @Final
    private ServerChunkManager.MainThreadExecutor mainThreadExecutor;

    @Shadow
    @Final
    private Thread serverThread;

    @Unique
    private final LossyChunkCache fastCache = new LossyChunkCache(16);

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
        if (Thread.currentThread() != this.serverThread) {
            return this.getChunkOffThread(x, z, step, create);
        } else {
            return this.getChunkOnThread(x, z, step, create);
        }
    }

    private Chunk getChunkOnThread(int x, int z, ChunkStep step, boolean create) {
        Chunk cached = this.fastCache.get(x, z, step);
        if (cached != null) {
            return cached;
        }

        if (create) {
            CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future = this.getChunkFuture(x, z, step.getMaximumStatus(), true);
            if (!future.isDone()) {
                this.mainThreadExecutor.runTasks(future::isDone);
            }

            Chunk chunk = future.join().map(
                    Function.identity(),
                    err -> { throw new IllegalStateException("Chunk not there when requested: " + err); }
            );

            this.fastCache.put(x, z, step, chunk);

            return chunk;
        } else {
            Chunk chunk = this.loadExistingChunk(x, z, step);
            this.fastCache.put(x, z, step, chunk);
            return chunk;
        }
    }

    private Chunk getChunkOffThread(int x, int z, ChunkStep step, boolean create) {
        if (create) {
            return this.getOrCreateChunkAsync(x, z, step).join();
        } else {
            return this.loadExistingChunk(x, z, step);
        }
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

    /**
     * @reason replace with implementation that will not return true for partially loaded chunks
     * @author gegy1000
     */
    @Overwrite
    public boolean isChunkLoaded(int x, int z) {
        return this.getExistingChunk(x, z, ChunkStep.FULL) != null;
    }

    private Chunk loadExistingChunk(int x, int z, ChunkStep step) {
        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future = this.getExistingChunkAsync(x, z, step);
        if (future == null) {
            return null;
        }

        Either<Chunk, ChunkHolder.Unloaded> result = future.getNow(null);
        if (result != null) {
            return result.map(chunk -> chunk, unloaded -> null);
        } else {
            return null;
        }
    }

    @Override
    public CompletableFuture<Chunk> getOrCreateChunkAsync(int x, int z, ChunkStep step) {
        return CompletableFuture.supplyAsync(() -> this.getChunkFuture(x, z, step.getMaximumStatus(), true), this.mainThreadExecutor)
                .thenCompose(Function.identity())
                .thenApply(result -> result.map(
                        chunk -> chunk,
                        unloaded -> {
                            throw new IllegalStateException("Chunk not there when requested: " + unloaded);
                        })
                );
    }

    @Nullable
    private CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> getExistingChunkAsync(int x, int z, ChunkStep step) {
        ChunkEntry entry = (ChunkEntry) this.getChunkHolder(ChunkPos.toLong(x, z));
        if (entry == null) {
            return null;
        }

        ChunkListener listener = entry.getValidListenerFor(step);
        if (listener != null) {
            return listener.asVanilla();
        } else {
            return null;
        }
    }

    @Shadow
    protected abstract CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> getChunkFuture(int x, int z, ChunkStatus status, boolean create);

    @Shadow
    @Nullable
    protected abstract ChunkHolder getChunkHolder(long pos);
}
