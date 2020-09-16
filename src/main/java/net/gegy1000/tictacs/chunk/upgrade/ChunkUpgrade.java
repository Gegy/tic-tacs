package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.tictacs.chunk.step.ChunkStep;

class ChunkUpgrade {
    final ChunkStep fromStep;
    final ChunkStep toStep;
    final ChunkUpgradeEntries entries;

    ChunkUpgrade(ChunkStep fromStep, ChunkStep toStep, ChunkUpgradeEntries entries) {
        this.fromStep = fromStep;
        this.toStep = toStep;
        this.entries = entries;
    }

    public boolean isEmpty() {
        return this.toStep.lessOrEqual(this.fromStep);
    }

    public ChunkUpgradeKernel getKernel() {
        return ChunkUpgradeKernel.betweenSteps(this.fromStep, this.toStep);
    }
}
