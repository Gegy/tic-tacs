package net.gegy1000.acttwo.chunk.loader.upgrade;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.future.VanillaChunkFuture;
import net.gegy1000.acttwo.chunk.loader.ChunkLoader;
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
    private static final Future<Unit> UNIT_FUTURE = Future.ready(Unit.INSTANCE);

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
        return entry.write().andThen(writeState -> {
            ChunkUpgrade upgrade;
            try {
                ChunkEntryState entryState = writeState.get();
                upgrade = entryState.tryBeginUpgrade(status);
            } finally {
                writeState.release();
            }

            // the requested upgrade was invalid: skip
            if (upgrade == null) {
                return UNIT_FUTURE;
            }

            // TODO: the upgrade future has to release these rwguards!
            // disabling for now: using the full context could potentially mean locking a bunch of chunks unnecessarily
//                ChunkContext fullContext = ChunkContext.forUpgrade(upgrade);
//                fullContext.spawn(this.controller.loader, entry.getPos());

            // TODO: clean up
            Future<Chunk> upgradeFuture;
            if (upgrade.from != null) {
                Future<ChunkEntry> fromFuture = entry.getListenerFor(upgrade.from);
                upgradeFuture = fromFuture.andThen(e -> this.runUpgrade(entry, upgrade));
            } else {
                upgradeFuture = this.runUpgrade(entry, upgrade);
            }

            return upgradeFuture.map(chunk -> Unit.INSTANCE);
        });
    }

    private Future<Chunk> runUpgrade(ChunkEntry entry, ChunkUpgrade upgrade) {
        ChunkLoader loader = this.controller.loader;
        GetChunkContextFuture context = loader.loadChunkContext(entry.getPos(), ChunkContext.forStatus(upgrade.steps[0]));
        return new UpgradeChunkFuture(loader, this, entry, upgrade, context);
    }

    Future<Chunk> runUpgradeTask(ChunkEntryState entryState, ChunkStatus status, List<Chunk> context) {
        return new VanillaChunkFuture(status.runTask(
                this.world,
                this.generator,
                this.structures,
                this.lighting,
                c -> this.finalizeChunk(entryState),
                context
        ));
    }

    Future<Chunk> runFinalizeTask(ChunkEntryState entryState, ChunkStatus status, Chunk chunk) {
        return new VanillaChunkFuture(status.runNoGenTask(
                this.world,
                this.structures,
                this.lighting,
                c -> this.finalizeChunk(entryState),
                chunk
        ));
    }

    private CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> finalizeChunk(ChunkEntryState entryState) {
        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> completable = new CompletableFuture<>();

        Future<ChunkEntry> fromFuture = entryState.parent.getListenerFor(ChunkStatus.FULL.getPrevious());
        this.controller.spawnOnMainThread(entryState.parent, fromFuture.map(entry -> {
            ChunkStatus targetStatus = ChunkHolder.getTargetGenerationStatus(entry.getLevel());
            if (!targetStatus.isAtLeast(ChunkStatus.FULL)) {
                completable.complete(ChunkHolder.UNLOADED_CHUNK);
                return Unit.INSTANCE;
            }

            WorldChunk chunk = entryState.finalizeChunk(this.world, this.controller.map::tryAddFullChunk);
            completable.complete(Either.left(chunk));

            return Unit.INSTANCE;
        }));

        return completable;
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
