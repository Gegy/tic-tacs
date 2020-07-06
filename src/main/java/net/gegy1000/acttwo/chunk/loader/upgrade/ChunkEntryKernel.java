package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.lock.RwGuard;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.util.Unit;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.function.Function;

final class ChunkEntryKernel {
    private final ChunkUpgradeKernel kernel;

    private boolean idle = true;

    private final Future<? extends RwGuard<ChunkEntryState>>[] entryFutures;
    private final RwGuard<ChunkEntryState>[] entries;

    // TODO: i don't like this solution
    private final BitSet writableEntries;

    private boolean acquiredAll;

    @SuppressWarnings("unchecked")
    ChunkEntryKernel(ChunkUpgradeKernel kernel) {
        this.kernel = kernel;

        int size = kernel.getSize();
        this.entryFutures = new Future[size * size];
        this.entries = new RwGuard[size * size];
        this.writableEntries = new BitSet(size * size);
    }

    public int prepareForUpgrade(ChunkMap chunkMap, ChunkPos pos, ChunkStatus targetStatus) {
        this.idle = false;

        int writeCount = 0;

        int centerX = pos.x;
        int centerZ = pos.z;
        int radius = this.kernel.getRadius();
        int size = this.kernel.getSize();

        Future<? extends RwGuard<ChunkEntryState>>[] futures = this.entryFutures;

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkEntry entry = chunkMap.getEntry(x + centerX, z + centerZ);
                if (entry == null) {
                    continue;
                }

                int idx = (x + radius) + (z + radius) * size;

                if (entry.canUpgradeTo(targetStatus)) {
                    futures[idx] = entry.write();
                    this.writableEntries.set(idx, true);
                    writeCount++;
                } else {
                    futures[idx] = entry.read();
                }
            }
        }

        return writeCount;
    }

    @Nullable
    public Unit pollEntries(Waker waker) {
        RwGuard<ChunkEntryState>[] entries = this.entries;
        if (this.acquiredAll) {
            return Unit.INSTANCE;
        }

        Future<? extends RwGuard<ChunkEntryState>>[] futures = this.entryFutures;

        boolean pending = false;

        for (int i = 0; i < entries.length; i++) {
            Future<? extends RwGuard<ChunkEntryState>> future = futures[i];
            if (future == null) {
                continue;
            }

            RwGuard<ChunkEntryState> poll = future.poll(waker);
            if (poll != null) {
                futures[i] = null;
                entries[i] = poll;
            } else {
                pending = true;
            }
        }

        if (!pending) {
            this.acquiredAll = true;
            return Unit.INSTANCE;
        }

        return null;
    }

    public <T> void openWriteTasks(Future<T>[] tasks, Function<ChunkEntryState, Future<T>> function) {
        RwGuard<ChunkEntryState>[] entries = this.entries;

        for (int i = 0; i < entries.length; i++) {
            RwGuard<ChunkEntryState> entry = entries[i];
            if (entry == null) continue;

            boolean writable = this.writableEntries.get(i);
            if (writable) {
                tasks[i] = function.apply(entry.get());
            }
        }
    }

    RwGuard<ChunkEntryState>[] getEntries() {
        return this.entries;
    }

    public void release() {
        Future<? extends RwGuard<ChunkEntryState>>[] futures = this.entryFutures;
        RwGuard<ChunkEntryState>[] entries = this.entries;

        for (int i = 0; i < entries.length; i++) {
            RwGuard<ChunkEntryState> entry = entries[i];
            if (entry != null) {
                entry.release();
            }

            entries[i] = null;
            futures[i] = null;
        }

        this.writableEntries.clear(0, this.writableEntries.size());

        this.idle = true;
        this.acquiredAll = false;
    }

    public boolean isIdle() {
        return this.idle;
    }

    public int getRadius() {
        return this.kernel.getRadius();
    }
}
