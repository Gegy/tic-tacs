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

    // TODO: there is a lot of iteration here
    public int prepareForUpgrade(ChunkMap chunks, ChunkPos pos, ChunkStatus targetStatus) {
        this.idle = false;

        int writeCount = this.addWritable(chunks, pos, targetStatus);
        if (writeCount <= 0) {
            return 0;
        }

        int radius = this.kernel.getRadius();
        int size = this.kernel.getSize();

        int taskMargin = targetStatus.getTaskMargin();
        if (taskMargin > 0) {
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    int idx = (x + radius) + (z + radius) * size;
                    if (this.writableEntries.get(idx)) {
                        this.addMargin(chunks, pos, x, z, taskMargin);
                    }
                }
            }
        }

        return writeCount;
    }

    private int addWritable(ChunkMap chunks, ChunkPos pos, ChunkStatus targetStatus) {
        int writeCount = 0;

        int centerX = pos.x;
        int centerZ = pos.z;
        int radius = this.kernel.getRadius();
        int size = this.kernel.getSize();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkEntry entry = chunks.getEntry(x + centerX, z + centerZ);
                if (entry == null) {
                    continue;
                }

                int idx = (x + radius) + (z + radius) * size;

                if (entry.canUpgradeTo(targetStatus)) {
                    this.entryFutures[idx] = entry.write();
                    this.writableEntries.set(idx, true);
                    writeCount++;
                }
            }
        }

        return writeCount;
    }

    private void addMargin(ChunkMap chunks, ChunkPos pos, int centerX, int centerZ, int margin) {
        Future<? extends RwGuard<ChunkEntryState>>[] entryFutures = this.entryFutures;

        int radius = this.kernel.getRadius();
        int size = this.kernel.getSize();

        int minX = Math.max(centerX - margin, -radius);
        int maxX = Math.min(centerX + margin, radius);
        int minZ = Math.max(centerZ - margin, -radius);
        int maxZ = Math.min(centerZ + margin, radius);

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int idx = (x + radius) + (z + radius) * size;

                if (entryFutures[idx] == null) {
                    ChunkEntry entry = chunks.getEntry(x + pos.x, z + pos.z);
                    if (entry == null) {
                        continue;
                    }

                    entryFutures[idx] = entry.read();
                }
            }
        }
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
