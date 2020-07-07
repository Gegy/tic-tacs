package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.ChunkStatusOps;
import net.minecraft.world.chunk.ChunkStatus;

public final class ChunkUpgrade {
    public final ChunkStatus from;
    public final ChunkStatus to;
    public final ChunkStatus[] steps;

    public ChunkUpgrade(ChunkStatus from, ChunkStatus to) {
        this.from = from;
        this.to = to;

        ChunkStatus[] steps = from != null ? ChunkStatusOps.stepsBetween(from, to) : ChunkStatusOps.stepsTo(to);
        this.steps = fixSteps(steps);
    }

    public boolean isEmpty() {
        return this.steps.length == 0;
    }

    private static ChunkStatus[] fixSteps(ChunkStatus[] steps) {
        // TODO
        return steps;
//		int fixedLength = steps.length;
//
//		for (ChunkStatus step : steps) {
//			if (step == ChunkStatus.HEIGHTMAPS) fixedLength--;
//			else if (step == ChunkStatus.LIGHT) fixedLength++;
//		}
//
//		ChunkStatus[] fixedSteps = new ChunkStatus[fixedLength];
//		int idx = 0;
//
//		for (ChunkStatus step : steps) {
//			// ignore heightmaps step: it does nothing
//			if (step == ChunkStatus.HEIGHTMAPS) continue;
//
//			// duplicate light step
//			if (step == ChunkStatus.LIGHT) {
//				fixedSteps[idx++] = step;
//			}
//
//			fixedSteps[idx++] = step;
//		}
//
//		return fixedSteps;
    }
}
