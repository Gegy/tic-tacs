package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.minecraft.world.chunk.ChunkStatus;

public final class ChunkUpgrade {
    public final ChunkStatus from;
    public final ChunkStatus to;
    public final ChunkStatus[] steps;

    public ChunkUpgrade(ChunkStatus from, ChunkStatus to) {
        this.from = from;
        this.to = to;
        this.steps = from != null ? stepsBetween(from, to) : stepsTo(to);
    }

    private static ChunkStatus[] stepsBetween(ChunkStatus start, ChunkStatus end) {
        ChunkStatus[] upgrades = new ChunkStatus[end.getIndex() - start.getIndex()];

        ChunkStatus status = end;
        while (status != start) {
            upgrades[status.getIndex() - start.getIndex() - 1] = status;
            status = status.getPrevious();
        }

        return upgrades;
    }

    private static ChunkStatus[] stepsTo(ChunkStatus end) {
        ChunkStatus[] upgrades = new ChunkStatus[end.getIndex() + 1];

        ChunkStatus status = end;
        int i = upgrades.length;
        while (i-- > 0) {
            upgrades[i] = status;
            status = status.getPrevious();
        }

        return upgrades;
    }
}
