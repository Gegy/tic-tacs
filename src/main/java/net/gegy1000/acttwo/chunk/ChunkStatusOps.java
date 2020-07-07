package net.gegy1000.acttwo.chunk;

import net.minecraft.world.chunk.ChunkStatus;

public final class ChunkStatusOps {
    public static ChunkStatus min(ChunkStatus left, ChunkStatus right) {
        if (!left.isAtLeast(right)) {
            return left;
        } else {
            return right;
        }
    }

    public static ChunkStatus max(ChunkStatus left, ChunkStatus right) {
        if (left.isAtLeast(right)) {
            return left;
        } else {
            return right;
        }
    }

    public static ChunkStatus[] stepsBetween(ChunkStatus start, ChunkStatus end) {
        if (start.isAtLeast(end)) {
            return new ChunkStatus[0];
        }

        ChunkStatus[] upgrades = new ChunkStatus[end.getIndex() - start.getIndex()];

        ChunkStatus status = end;
        while (status != start) {
            upgrades[status.getIndex() - start.getIndex() - 1] = status;
            status = status.getPrevious();
        }

        return upgrades;
    }

    public static ChunkStatus[] stepsTo(ChunkStatus end) {
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
