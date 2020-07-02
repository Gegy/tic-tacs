package net.gegy1000.acttwo.chunk.future;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.AsyncChunkState;
import net.gegy1000.acttwo.chunk.ChunkHolderExt;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.TacsExt;
import net.gegy1000.acttwo.mixin.TacsAccessor;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class UpgradeChunk implements Future<Chunk> {
    private final ThreadedAnvilChunkStorage tacs;
    private final ChunkHolder holder;

    private final ChunkStatus[] upgrades;
    private final GetChunkContext upgradeContext;

    private int upgradeIndex;

    private Chunk intermediaryChunk;

    private Future<Chunk> runTask;
    private Future<Chunk> finalizeTask;

    public UpgradeChunk(ThreadedAnvilChunkStorage tacs, ChunkHolder holder, ChunkStatus[] upgrades) {
        this.tacs = tacs;
        this.holder = holder;

        this.upgrades = upgrades;

        this.upgradeContext = ((TacsExt) tacs).getChunkContext(holder.getPos(), this.upgrades);
    }

    public static ChunkStatus[] upgradesBetween(ChunkStatus start, ChunkStatus end) {
        ChunkStatus[] upgrades = new ChunkStatus[end.getIndex() - start.getIndex()];

        ChunkStatus status = end;
        while (status != start) {
            upgrades[status.getIndex() - start.getIndex() - 1] = status;
            status = status.getPrevious();
        }

        return upgrades;
    }

    @Nullable
    @Override
    public Chunk poll(Waker waker) {
        while (this.pollUpgradeStep(waker, this.upgradeIndex)) {
            boolean complete = ++this.upgradeIndex >= this.upgrades.length;
            if (!complete) {
                this.upgradeContext.upgradeTo(this.upgrades[this.upgradeIndex]);
            } else {
                return this.intermediaryChunk;
            }
        }

        return null;
    }

    private boolean pollUpgradeStep(Waker waker, int index) {
        List<Chunk> context = this.upgradeContext.poll(waker);
        if (context == null) {
            return false;
        }

        ChunkStatus status = this.upgrades[index];

        try {
            Chunk upgraded = this.upgradeAndFinalize(waker, status, context);
            if (upgraded == null) {
                return false;
            }

            this.intermediaryChunk = upgraded;

            this.notifyUpgrade(status, upgraded);

            this.runTask = null;
            this.finalizeTask = null;
        } catch (ChunkNotLoadedException err) {
            this.notifyErr(status, err);
            throw err;
        }

        return true;
    }

    private Chunk upgradeAndFinalize(Waker waker, ChunkStatus status, List<Chunk> context) {
        if (this.finalizeTask != null) {
            return this.finalizeTask.poll(waker);
        }

        Chunk chunk = this.upgrade(waker, status, context);
        if (chunk != null) {
            if (status == ChunkStatus.FULL) {
                this.finalizeTask = this.finalizeUpgrade(chunk);
                return this.finalizeTask.poll(waker);
            }

            return chunk;
        }

        return null;
    }

    private Chunk upgrade(Waker waker, ChunkStatus status, List<Chunk> context) {
        if (this.runTask == null) {
            TacsAccessor tacsAccessor = (TacsAccessor) this.tacs;

            CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> task = status.runTask(
                    tacsAccessor.getWorld(),
                    tacsAccessor.getChunkGenerator(),
                    tacsAccessor.getStructureManager(),
                    tacsAccessor.getServerLightingProvider(),
                    chunk -> ((TacsExt) this.tacs).accessConvertToFullChunk(this.holder),
                    context
            );

            this.runTask = new VanillaChunkFuture(task);
        }

        return this.runTask.poll(waker);
    }

    private Future<Chunk> finalizeUpgrade(Chunk chunk) {
        TacsAccessor tacsAccessor = (TacsAccessor) this.tacs;

        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future = ChunkStatus.FULL.runNoGenTask(
                tacsAccessor.getWorld(),
                tacsAccessor.getStructureManager(),
                tacsAccessor.getServerLightingProvider(),
                c -> ((TacsExt) this.tacs).accessConvertToFullChunk(this.holder),
                chunk
        );

        return new VanillaChunkFuture(future);
    }

    private void notifyUpgrade(ChunkStatus status, Chunk upgraded) {
        AsyncChunkState asyncState = ((ChunkHolderExt) this.holder).getAsyncState();
        asyncState.completeOk(status, upgraded);

        TacsAccessor tacsAccessor = (TacsAccessor) this.tacs;

        WorldGenerationProgressListener progress = tacsAccessor.getWorldGenerationProgressListener();
        progress.setChunkStatus(this.holder.getPos(), status);

        if (status == ChunkStatus.LIGHT) {
            ChunkPos pos = this.holder.getPos();

            tacsAccessor.getMainThreadExecutor().submit(() -> {
                int ticketLevel = 33 + ChunkStatus.getTargetGenerationRadius(ChunkStatus.FEATURES);
                tacsAccessor.getTicketManager().addTicketWithLevel(ChunkTicketType.LIGHT, pos, ticketLevel, pos);
            });
        }
    }

    private void notifyErr(ChunkStatus status, ChunkNotLoadedException err) {
        AsyncChunkState asyncState = ((ChunkHolderExt) this.holder).getAsyncState();
        asyncState.completeErr(status, ChunkHolder.Unloaded.INSTANCE);
    }
}
