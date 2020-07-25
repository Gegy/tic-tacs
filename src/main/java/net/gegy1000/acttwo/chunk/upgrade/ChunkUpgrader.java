package net.gegy1000.acttwo.chunk.upgrade;

import net.gegy1000.acttwo.async.lock.Semaphore;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkNotLoadedException;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.step.ChunkStep;
import net.gegy1000.acttwo.chunk.step.ChunkStepContext;
import net.gegy1000.acttwo.chunk.worker.ChunkWorker;
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
        // avoid spawning a future if we know the upgrade will definitely be invalid
        if (!entry.canUpgradeTo(step)) {
            return;
        }

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
        return step.run(new ChunkStepContext(this.controller, entry, this.world, this.generator, this.structures, this.lighting, entry.getChunk(), chunks));
    }

    void completeUpgradeOk(ChunkEntryState entry, ChunkStep step, Chunk chunk) {
        entry.completeUpgradeOk(step, chunk);

        ChunkStatus status = step.getMaximumStatus();

        this.controller.notifyStatus(entry.getPos(), status);
        if (chunk instanceof ProtoChunk) {
            ((ProtoChunk) chunk).setStatus(status);
        }
    }

    void completeUpgradeErr(ChunkEntryState entry, ChunkStep step, ChunkNotLoadedException err) {
        entry.completeUpgradeErr(step, err);
        this.controller.notifyStatus(entry.getPos(), null);
    }
}
