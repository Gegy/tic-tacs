package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.Mutability;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.loader.ChunkLoader;
import net.gegy1000.acttwo.lock.RwGuard;
import net.gegy1000.justnow.future.Future;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.List;
import java.util.function.IntFunction;

// TODO: split for awaiting vs spawning? one needs mutability and the other does not
public final class ChunkContext {
    private static final ChunkContext[] FOR_STATUS;

    static {
        List<ChunkStatus> statuses = ChunkStatus.createOrderedList();

        FOR_STATUS = new ChunkContext[statuses.size()];
        for (int i = 0; i < statuses.size(); i++) {
            ChunkStatus status = statuses.get(i);
            int radius = status.getTaskMargin();
            int targetRadius = ChunkStatus.getTargetGenerationRadius(status);

            Mutability mutability = ChunkStatusRequirements.getContextMutability(status);
            FOR_STATUS[i] = new ChunkContext(radius, mutability, distance -> {
                if (distance == 0) {
                    // we don't want to have any requirement on the status of the current chunk
                    return status.getPrevious();
                } else {
                    return ChunkStatus.getTargetGenerationStatus(targetRadius + distance);
                }
            });
        }
    }

    private final int radius;
    private final Mutability mutability;
    private final IntFunction<ChunkStatus> distanceToStatus;

    public ChunkContext(int radius, Mutability mutability, IntFunction<ChunkStatus> distanceToStatus) {
        this.radius = radius;
        this.mutability = mutability;
        this.distanceToStatus = distanceToStatus;
    }

    // TODO
    /*public static ChunkContext forUpgrade(ChunkUpgrade upgrade) {
        ChunkStatus firstStep = upgrade.steps[0];
        int maxRadius = Integer.MIN_VALUE;
        int minTargetRadius = Integer.MAX_VALUE;
        int mutableRadius = 0;

        for (ChunkStatus status : upgrade.steps) {
            maxRadius = Math.max(maxRadius, status.getTaskMargin());
            minTargetRadius = Math.min(minTargetRadius, ChunkStatus.getTargetGenerationRadius(status));

            Mutability contextMutability = ChunkStatusRequirements.getContextMutability(status);
            if (contextMutability == Mutability.MUTABLE) {
                mutableRadius = Math.max(mutableRadius, status.getTaskMargin());
            }
        }

        return new ChunkContext(firstStep, maxRadius, minTargetRadius, mutableRadius);
    }*/

    public static ChunkContext forStatus(ChunkStatus status) {
        return FOR_STATUS[status.getIndex()];
    }

    @SuppressWarnings("unchecked")
    public Future<RwGuard<Chunk>>[] spawn(ChunkLoader loader, ChunkPos centerChunk) {
        int centerX = centerChunk.x;
        int centerZ = centerChunk.z;

        // for what purpose, mojang, do you have -1 radius
        int radius = Math.max(this.radius, 0);

        int diameter = radius * 2 + 1;
        IntFunction<ChunkStatus> distanceToStatus = this.distanceToStatus;
        Mutability mutability = this.mutability;

        Future<RwGuard<Chunk>>[] futures = new Future[diameter * diameter];

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * diameter;
                int distance = Math.max(Math.abs(x), Math.abs(z));

                ChunkStatus status = distanceToStatus.apply(distance);

                if (mutability == Mutability.MUTABLE) {
                    futures[idx] = loader.writeChunkAs(x + centerX, z + centerZ, status);
                } else {
                    futures[idx] = loader.readChunkAs(x + centerX, z + centerZ, status);
                }
            }
        }

        return futures;
    }

    @SuppressWarnings("unchecked")
    public Future<ChunkEntry>[] await(ChunkMap map, ChunkPos centerChunk) {
        int centerX = centerChunk.x;
        int centerZ = centerChunk.z;

        int radius = this.radius;
        int diameter = radius * 2 + 1;
        IntFunction<ChunkStatus> distanceToStatus = this.distanceToStatus;

        Future<ChunkEntry>[] futures = new Future[diameter * diameter];

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int idx = (x + radius) + (z + radius) * diameter;
                int distance = Math.max(Math.abs(x), Math.abs(z));

                ChunkStatus status = distanceToStatus.apply(distance);

                // TODO: handle null entries
                ChunkEntry entry = map.getEntry(x + centerX, z + centerZ);
                futures[idx] = entry.getListenerFor(status);
            }
        }

        return futures;
    }
}
