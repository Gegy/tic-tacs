package net.gegy1000.acttwo.chunk.tracker;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;

import java.util.function.Consumer;

final class ChunkTrackKernel {
    public final int minX;
    public final int minZ;
    public final int maxX;
    public final int maxZ;

    public ChunkTrackKernel(int minX, int minZ, int maxX, int maxZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    public static ChunkTrackKernel withRadius(ChunkSectionPos sectionPos, int radius) {
        return ChunkTrackKernel.withRadius(sectionPos.getSectionX(), sectionPos.getSectionZ(), radius);
    }

    public static ChunkTrackKernel withRadius(int chunkX, int chunkZ, int radius) {
        return new ChunkTrackKernel(chunkX - radius, chunkZ - radius, chunkX + radius, chunkZ + radius);
    }

    public static ChunkTrackKernel withRadius(ServerPlayerEntity player, int radius) {
        int chunkX = MathHelper.floor(player.getX()) >> 4;
        int chunkZ = MathHelper.floor(player.getZ()) >> 4;
        return ChunkTrackKernel.withRadius(chunkX, chunkZ, radius);
    }

    public void forEach(Consumer<ChunkPos> consumer) {
        for (int z = this.minZ; z <= this.maxZ; z++) {
            for (int x = this.minX; x <= this.maxX; x++) {
                consumer.accept(new ChunkPos(x, z));
            }
        }
    }

    public boolean intersects(ChunkTrackKernel kernel) {
        return this.minX < kernel.maxX && this.maxX > kernel.minX && this.minZ < kernel.maxZ && this.maxZ > kernel.minZ;
    }

    public ChunkTrackKernel union(ChunkTrackKernel kernel) {
        return new ChunkTrackKernel(
                Math.min(this.minX, kernel.minX),
                Math.min(this.minZ, kernel.minZ),
                Math.max(this.maxX, kernel.maxX),
                Math.max(this.maxZ, kernel.maxZ)
        );
    }
}
