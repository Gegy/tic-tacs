package net.gegy1000.acttwo.chunk;

import com.mojang.datafixers.util.Either;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;

import javax.annotation.Nullable;
import java.util.List;

public final class AsyncChunkState {
    private static final List<ChunkStatus> TIERS = ChunkStatus.createOrderedList();

    private final ChunkHolder holder;

    private final ChunkListener[] listeners = new ChunkListener[TIERS.size()];

    private final Object mutex = new Object();

    private final ChunkListener mainListener = new ChunkListener();
    private ChunkStatus loadingStatus;

    public AsyncChunkState(ChunkHolder holder) {
        this.holder = holder;
        for (ChunkStatus status : TIERS) {
            this.listeners[status.getIndex()] = new ChunkListener();
        }
    }

    public ChunkListener getListenerFor(ChunkStatus status) {
        return this.listeners[status.getIndex()];
    }

    public void upgradeTo(ThreadedAnvilChunkStorage tacs, ChunkStatus status, SpawnUpgrade upgrade) {
        // TODO: can we avoid locking?
        synchronized (this.mutex) {
            if (this.loadingStatus != null) {
                if (this.loadingStatus.isAtLeast(status)) {
                    return;
                }
                this.upgradeFrom(this.loadingStatus, status, upgrade);
            } else {
                this.upgradeFromRoot(tacs, status, upgrade);
            }

            this.beginLoading(status);
        }
    }

    private void upgradeFrom(ChunkStatus fromStatus, ChunkStatus toStatus, SpawnUpgrade upgrade) {
        upgrade.spawn(this.mainListener, this.holder, fromStatus, toStatus);
    }

    private void upgradeFromRoot(ThreadedAnvilChunkStorage tacs, ChunkStatus status, SpawnUpgrade upgrade) {
        Future<Chunk> load = ((TacsExt) tacs).spawnLoadChunk(this.holder);
        if (status != ChunkStatus.EMPTY) {
            upgrade.spawn(load, this.holder, ChunkStatus.EMPTY, status);
        }
    }

    private void beginLoading(ChunkStatus status) {
        if (status.isAtLeast(status)) {
            this.loadingStatus = status;
            ((ChunkHolderExt) this.holder).combineFuture(this.mainListener.completable);
        }
    }

    public void complete(ChunkStatus status, Either<Chunk, ChunkHolder.Unloaded> result) {
        if (this.loadingStatus != null && this.loadingStatus.isAtLeast(status)) {
            this.mainListener.complete(result);
        }

        while (status != null) {
            this.getListenerFor(status).complete(result);
            status = prevOrNull(status);
        }
    }

    public void completeOk(ChunkStatus status, Chunk chunk) {
        this.complete(status, Either.left(chunk));
    }

    public void completeErr(ChunkStatus status, ChunkHolder.Unloaded err) {
        this.complete(status, Either.right(err));
    }

    public void finalizeAs(ReadOnlyChunk chunk) {
        for (ChunkListener listener : this.listeners) {
            if (listener.result instanceof ProtoChunk) {
                listener.result = chunk;
            }
        }
    }

    public void reduceStatus(ChunkStatus from, @Nullable ChunkStatus to) {
        int startIdx = to != null ? to.getIndex() + 1 : 0;
        int endIdx = from.getIndex();

        for (int i = startIdx; i <= endIdx; i++) {
            this.listeners[i].completeErr(new ChunkNotLoadedException(this.holder.getPos()));
        }
    }

    @Nullable
    private static ChunkStatus prevOrNull(ChunkStatus status) {
        return status == ChunkStatus.EMPTY ? null : status.getPrevious();
    }

    public interface SpawnUpgrade {
        void spawn(
                Future<Chunk> fromFuture,
                ChunkHolder holder,
                ChunkStatus fromStatus,
                ChunkStatus toStatus
        );
    }
}
