package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.async.lock.Lock;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.world.chunk.Chunk;

import org.jetbrains.annotations.Nullable;

final class ChunkLoadFuture implements Future<Chunk> {
    final ChunkController controller;
    final ChunkEntry entry;

    volatile Future<Unit> acquireLock;
    volatile Future<Chunk> loadFuture;

    public ChunkLoadFuture(ChunkController controller, ChunkEntry entry) {
        this.controller = controller;
        this.entry = entry;
    }

    @Nullable
    @Override
    public Chunk poll(Waker waker) {
        Lock upgradeLock = this.entry.getLock().upgrade();

        if (this.loadFuture == null) {
            if (this.acquireLock == null) {
                this.acquireLock = upgradeLock.acquireAsync();
            }

            if (this.acquireLock.poll(waker) == null) {
                return null;
            }

            this.loadFuture = this.controller.spawnLoadChunk(this.entry);
        }

        Chunk chunk = this.loadFuture.poll(waker);
        if (chunk == null) {
            return null;
        }

        ChunkUpgrader upgrader = this.controller.getUpgrader();
        upgrader.notifyUpgradeOk(this.entry, ChunkStep.EMPTY, chunk);

        upgradeLock.release();

        return chunk;
    }
}
