package net.gegy1000.tictacs.chunk.upgrade;

import com.google.common.collect.Iterators;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Iterator;

final class ChunkUpgradeEntries implements Iterable<ChunkEntry> {
    final ChunkUpgradeKernel kernel;
    final ChunkEntry[] entries;

    volatile ChunkStep minimumStep;
    volatile boolean acquired;

    ChunkUpgradeEntries(ChunkUpgradeKernel kernel) {
        this.kernel = kernel;
        this.entries = kernel.create(ChunkEntry[]::new);
    }

    boolean tryAcquire(ChunkAccess chunks, ChunkPos pos, ChunkUpgradeKernel kernel) {
        int originX = pos.x;
        int originZ = pos.z;

        ChunkStep minimumStep = ChunkStep.FULL;

        int radius = kernel.getRadius();

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                ChunkEntry entry = chunks.getEntry(x + originX, z + originZ);

                // all chunk entries must be available before upgrading
                if (entry == null) {
                    Arrays.fill(this.entries, null);
                    return false;
                }

                this.entries[kernel.index(x, z)] = entry;

                // we've reached the absolute minimum status
                if (minimumStep == null) {
                    continue;
                }

                ChunkStep step = entry.getCurrentStep();
                if (step == null || step.lessThan(minimumStep)) {
                    minimumStep = step;
                }
            }
        }

        this.acquired = true;

        return true;
    }

    @Nonnull
    ChunkEntry getEntry(int x, int z) {
        return this.entries[this.kernel.index(x, z)];
    }

    @Override
    public Iterator<ChunkEntry> iterator() {
        return Iterators.forArray(this.entries);
    }
}
