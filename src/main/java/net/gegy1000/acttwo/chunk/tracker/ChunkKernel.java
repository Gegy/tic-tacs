package net.gegy1000.acttwo.chunk.tracker;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;

import java.util.function.Consumer;

public final class ChunkKernel {
    public final int minX;
    public final int minZ;
    public final int maxX;
    public final int maxZ;

    public ChunkKernel(int minX, int minZ, int maxX, int maxZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    public static ChunkKernel withRadius(ChunkSectionPos sectionPos, int radius) {
        return ChunkKernel.withRadius(sectionPos.getSectionX(), sectionPos.getSectionZ(), radius);
    }

    public static ChunkKernel withRadius(int chunkX, int chunkZ, int radius) {
        return new ChunkKernel(chunkX - radius, chunkZ - radius, chunkX + radius, chunkZ + radius);
    }

    public static ChunkKernel withRadius(ServerPlayerEntity player, int radius) {
        int chunkX = MathHelper.floor(player.getX()) >> 4;
        int chunkZ = MathHelper.floor(player.getZ()) >> 4;
        return ChunkKernel.withRadius(chunkX, chunkZ, radius);
    }

    public void forEach(Consumer<ChunkPos> consumer) {
        for (int z = this.minZ; z <= this.maxZ; z++) {
            for (int x = this.minX; x <= this.maxX; x++) {
                consumer.accept(new ChunkPos(x, z));
            }
        }
    }

    public boolean intersects(ChunkKernel kernel) {
        return this.minX < kernel.maxX && this.maxX > kernel.minX && this.minZ < kernel.maxZ && this.maxZ > kernel.minZ;
    }

    public ChunkKernel union(ChunkKernel kernel) {
        return new ChunkKernel(
                Math.min(this.minX, kernel.minX),
                Math.min(this.minZ, kernel.minZ),
                Math.max(this.maxX, kernel.maxX),
                Math.max(this.maxZ, kernel.maxZ)
        );
    }
}
