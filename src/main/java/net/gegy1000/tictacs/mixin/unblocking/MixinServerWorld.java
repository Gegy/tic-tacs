package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Supplier;

@Mixin(ServerWorld.class)
public abstract class MixinServerWorld extends World implements NonBlockingWorldAccess {
    @Shadow
    @Final
    private ServerChunkManager serverChunkManager;

    private MixinServerWorld(MutableWorldProperties properties, RegistryKey<World> registryKey, DimensionType dimensionType, Supplier<Profiler> supplier, boolean client, boolean debugWorld, long biomeSeed) {
        super(properties, registryKey, dimensionType, supplier, client, debugWorld, biomeSeed);
    }

    /**
     * @reason don't block to load chunk if it is not already loaded
     * @author gegy1000
     */
    @Overwrite
    public void checkEntityChunkPos(Entity entity) {
        if (!entity.isChunkPosUpdateRequested()) {
            return;
        }

        this.getProfiler().push("chunkCheck");

        int chunkX = MathHelper.floor(entity.getX()) >> 4;
        int chunkY = MathHelper.floor(entity.getY()) >> 4;
        int chunkZ = MathHelper.floor(entity.getZ()) >> 4;

        // "updateNeeded" is more "inChunk"
        if (!entity.updateNeeded || entity.chunkX != chunkX || entity.chunkY != chunkY || entity.chunkZ != chunkZ) {
            if (entity.updateNeeded && this.isChunkLoaded(entity.chunkX, entity.chunkZ)) {
                this.getChunk(entity.chunkX, entity.chunkZ).remove(entity, entity.chunkY);
            }

            if (entity.teleportRequested() || this.isChunkReady(chunkX, chunkZ)) {
                this.getChunk(chunkX, chunkZ).addEntity(entity);
            } else {
                entity.updateNeeded = false;
            }
        }

        this.getProfiler().pop();
    }

    @Override
    public boolean isChunkReady(int x, int z) {
        return this.serverChunkManager.getChunk(x, z, ChunkStatus.FULL, false) != null;
    }
}
