package net.gegy1000.acttwo.chunk.loader.upgrade;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.future.VanillaChunkFuture;
import net.gegy1000.acttwo.chunk.tracker.ChunkLeveledTracker;
import net.gegy1000.acttwo.chunk.worker.ChunkWorker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ChunkUpgrader {
    private final ChunkWorker worker = ChunkWorker.INSTANCE;

    private final ChunkController controller;

    private final ServerWorld world;
    private final ChunkGenerator generator;
    private final StructureManager structures;
    private final ServerLightingProvider lighting;

    public ChunkUpgrader(
            ServerWorld world,
            ChunkController controller,
            ChunkGenerator generator,
            StructureManager structures,
            ServerLightingProvider lighting
    ) {
        this.world = world;
        this.generator = generator;
        this.structures = structures;
        this.lighting = lighting;

        this.controller = controller;
    }

    public void spawnUpgradeTo(ChunkEntry entry, ChunkStatus status) {
        // avoid spawning a future if we know the upgrade will definitely be invalid
        if (!entry.canUpgradeTo(status)) {
            return;
        }

        this.worker.spawn(entry, this.upgradeTo(entry, status));
    }

    private Future<Unit> upgradeTo(ChunkEntry entry, ChunkStatus status) {
        // TODO: pool instances
        return new ChunkUpgradeFuture(this.controller, entry.getPos(), status);
    }

    Future<Chunk> runUpgradeTask(ChunkEntryState entry, ChunkStatus status, List<Chunk> context) {
        return VanillaChunkFuture.of(status.runTask(
                this.world,
                this.generator,
                this.structures,
                this.lighting,
                chunk -> this.runFinalizeTask(entry, status, chunk),
                context
        ));
    }

    CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> runFinalizeTask(ChunkEntryState entry, ChunkStatus status, Chunk chunk) {
        if (status == ChunkStatus.FULL) {
            CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> completableFuture = new CompletableFuture<>();

            this.controller.spawnOnMainThread(entry.parent, () -> {
                WorldChunk worldChunk = entry.finalizeChunk(this.world, this.controller.map::tryAddFullChunk);
                completableFuture.complete(Either.left(worldChunk));
            });

            return completableFuture;
        }

        return CompletableFuture.completedFuture(Either.left(chunk));
    }

    void completeUpgradeOk(ChunkEntryState entry, ChunkStatus status, Chunk chunk) {
        entry.completeUpgradeOk(status, chunk);

        ChunkLeveledTracker leveledTracker = this.controller.tracker.leveledTracker;

        ChunkPos pos = entry.getPos();
        this.controller.loader.notifyStatus(pos, status);

        if (status == ChunkStatus.LIGHT) {
            this.controller.spawnOnMainThread(entry.parent, () -> {
                int ticketLevel = 33 + ChunkStatus.getTargetGenerationRadius(ChunkStatus.FEATURES);
                leveledTracker.addTicketWithLevel(ChunkTicketType.LIGHT, pos, ticketLevel, pos);
            });
        }
    }

    void completeUpgradeErr(ChunkEntryState entry, ChunkStatus status, ChunkNotLoadedException err) {
        entry.completeUpgradeErr(status, err);
        this.controller.loader.notifyStatus(entry.getPos(), null);
    }
}
