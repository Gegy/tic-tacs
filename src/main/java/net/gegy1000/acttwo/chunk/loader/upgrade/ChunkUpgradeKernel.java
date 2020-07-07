package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.minecraft.world.chunk.ChunkStatus;

final class ChunkUpgradeKernel {
    private static final ChunkStatus[] STATUSES = ChunkStatus.createOrderedList().toArray(new ChunkStatus[0]);

    private static final ChunkUpgradeKernel[] BY_STATUS = new ChunkUpgradeKernel[STATUSES.length];

    static {
        for (ChunkStatus status : STATUSES) {
            BY_STATUS[status.getIndex()] = createForStatus(status);
        }
    }

    private final int radius;
    private final int size;

    private final ChunkStatus[] table;

    private ChunkUpgradeKernel(int radius, int size, ChunkStatus[] table) {
        this.radius = radius;
        this.size = size;
        this.table = table;
    }

    public static ChunkUpgradeKernel byStatus(ChunkStatus status) {
        return BY_STATUS[status.getIndex()];
    }

    public int getSize() {
        return this.size;
    }

    public int getRadius() {
        return this.radius;
    }

    public ChunkStatus get(int x, int z) {
        int idx = this.index(x, z);
        return this.table[idx];
    }

    public int index(int x, int z) {
        return (x + this.radius) + (z + this.radius) * this.size;
    }

    private static ChunkUpgradeKernel createForStatus(ChunkStatus focusStatus) {
        int radius = resolveRadiusFor(focusStatus);
        int size = radius * 2 + 1;

        int focusGenerationRadius = ChunkStatus.getTargetGenerationRadius(focusStatus);

        ChunkStatus[] table = new ChunkStatus[size * size];
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * size;
                int distance = Math.max(Math.abs(x), Math.abs(z));

                ChunkStatus status = focusStatus;
                if (distance > 0) {
                    status = ChunkStatus.getTargetGenerationStatus(focusGenerationRadius + distance);
                }

                table[idx] = status;
            }
        }

        return new ChunkUpgradeKernel(radius, size, table);
    }

    private static int resolveRadiusFor(ChunkStatus focusStatus) {
        int focusGenerationRadius = ChunkStatus.getTargetGenerationRadius(focusStatus);

        int maxRadius = 0;
        for (int distance = 0; distance <= maxRadius; distance++) {
            ChunkStatus status = ChunkStatus.getTargetGenerationStatus(focusGenerationRadius + distance);

            int radius = distance + getMaxMarginFor(status);
            maxRadius = Math.max(maxRadius, radius);
        }

        return maxRadius;
    }

    private static int getMaxMarginFor(ChunkStatus focusStatus) {
        int maxMargin = 0;

        ChunkStatus currentStatus = focusStatus;
        while (currentStatus != ChunkStatus.EMPTY) {
            maxMargin = Math.max(maxMargin, currentStatus.getTaskMargin());
            currentStatus = currentStatus.getPrevious();
        }

        return maxMargin;
    }
}
