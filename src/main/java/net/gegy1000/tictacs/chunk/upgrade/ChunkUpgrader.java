package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.tictacs.async.lock.Semaphore;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkNotLoadedException;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.entry.ChunkEntryState;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.tictacs.chunk.step.ChunkStepContext;
import net.gegy1000.tictacs.chunk.worker.ChunkWorker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;

import java.util.List;

public final class ChunkUpgrader {
    private final ChunkWorker worker = ChunkWorker.INSTANCE;

    private final ChunkController controller;

    private final ServerWorld world;
    private final ChunkGenerator generator;
    private final StructureManager structures;
    private final ServerLightingProvider lighting;

    public final Semaphore lightingThrottler = new Semaphore(16);

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

    public void spawnUpgradeTo(ChunkEntry entry, ChunkStep step) {
        if (entry.trySpawnUpgradeTo(step)) {
            this.worker.spawn(entry, this.upgradeTo(entry, step));
        }
    }

    private Future<Unit> upgradeTo(ChunkEntry entry, ChunkStep step) {
        // TODO: pool instances
        return new ChunkUpgradeFuture(this.controller, entry.getPos(), step);
    }

    Future<Chunk> runStepTask(ChunkEntryState entry, ChunkStep step, List<Chunk> chunks) {
        // TODO: reuse context objects
        ChunkStepContext context = new ChunkStepContext(this.controller, entry, this.world, this.generator, this.structures, this.lighting, entry.getChunk(), chunks);

        if (this.hasAlreadyUpgradedTo(entry, step)) {
            return step.runLoad(context);
        } else {
            return step.runUpgrade(context);
        }
    }

    private boolean hasAlreadyUpgradedTo(ChunkEntryState entry, ChunkStep step) {
        ProtoChunk currentChunk = entry.getChunk();
        return currentChunk != null && currentChunk.getStatus().isAtLeast(step.getMaximumStatus());
    }

    void completeUpgradeOk(ChunkEntryState entry, ChunkStep step, Chunk chunk) {
        entry.completeUpgradeOk(step, chunk);

        ChunkStatus status = step.getMaximumStatus();

        this.controller.notifyStatus(entry.getPos(), status);
        ChunkStep.trySetStatus(chunk, status);
    }

    void completeUpgradeErr(ChunkEntryState entry, ChunkStep step, ChunkNotLoadedException err) {
        entry.completeUpgradeErr(step, err);
        this.controller.notifyStatus(entry.getPos(), null);
    }
}
