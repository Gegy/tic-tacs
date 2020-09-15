package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.tictacs.chunk.step.ChunkStep;

class ChunkUpgrade {
    static final ChunkUpgrade EMPTY = new ChunkUpgrade(null, null, null);

    final ChunkStep fromStep;
    final ChunkStep toStep;
    final ChunkUpgradeEntries entries;

    ChunkUpgrade(ChunkStep fromStep, ChunkStep toStep, ChunkUpgradeEntries entries) {
        this.fromStep = fromStep;
        this.toStep = toStep;
        this.entries = entries;
    }

    public boolean isEmpty() {
        return this.entries == null;
    }

    public ChunkUpgradeKernel getKernel() {
        return ChunkUpgradeKernel.betweenSteps(this.fromStep, this.toStep);
    }
}
