package net.gegy1000.acttwo.chunk;

import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.List;

public final class ChunkContext {
    private static final ChunkContext[] FOR_STATUS;

    static {
        List<ChunkStatus> statuses = ChunkStatus.createOrderedList();

        FOR_STATUS = new ChunkContext[statuses.size()];
        for (int i = 0; i < statuses.size(); i++) {
            ChunkStatus status = statuses.get(i);
            int radius = status.getTaskMargin();
            int generationRadius = ChunkStatus.getTargetGenerationRadius(status);
            FOR_STATUS[i] = new ChunkContext(status, radius, generationRadius);
        }
    }

    private final ChunkStatus status;
    private final int radius;
    private final int targetRadius;

    public ChunkContext(ChunkStatus status, int radius, int targetRadius) {
        this.status = status;
        this.radius = radius;
        this.targetRadius = targetRadius;
    }

    public static ChunkContext forRange(ChunkStatus... statuses) {
        ChunkStatus firstStatus = statuses[0];
        int maxRadius = Integer.MIN_VALUE;
        int minTargetRadius = Integer.MAX_VALUE;

        for (ChunkStatus status : statuses) {
            maxRadius = Math.max(maxRadius, status.getTaskMargin());
            minTargetRadius = Math.min(minTargetRadius, ChunkStatus.getTargetGenerationRadius(status));
        }

        return new ChunkContext(firstStatus, maxRadius, minTargetRadius);
    }

    public static ChunkContext forStatus(ChunkStatus status) {
        return FOR_STATUS[status.getIndex()];
    }

    @SuppressWarnings("unchecked")
    public Future<Chunk>[] spawn(ThreadedAnvilChunkStorage tacs, ChunkPos centerChunk) {
        int centerX = centerChunk.x;
        int centerZ = centerChunk.z;

        int radius = this.radius;
        int diameter = radius * 2 + 1;
        int targetRadius = this.targetRadius;

        Future<Chunk>[] futures = new Future[diameter * diameter];

        TacsExt tacsExt = (TacsExt) tacs;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * diameter;
                int distance = Math.max(Math.abs(x), Math.abs(z));

                ChunkStatus status;
                if (distance == 0) {
                    // we don't want to have any requirement on the status of the current chunk
                    status = this.status.getPrevious();
                } else {
                    status = ChunkStatus.getTargetGenerationStatus(targetRadius + distance);
                }

                futures[idx] = tacsExt.getChunk(x + centerX, z + centerZ, status);
            }
        }

        return futures;
    }
}
