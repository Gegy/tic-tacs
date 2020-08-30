package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.AsyncChunkAccess;
import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Mixin(ServerWorld.class)
public abstract class MixinServerWorld extends World implements NonBlockingWorldAccess, AsyncChunkAccess {
    @Shadow
    @Final
    private ServerChunkManager serverChunkManager;

    private MixinServerWorld(MutableWorldProperties properties, RegistryKey<World> registryKey, DimensionType dimensionType, Supplier<Profiler> supplier, boolean client, boolean debugWorld, long biomeSeed) {
        super(properties, registryKey, dimensionType, supplier, client, debugWorld, biomeSeed);
    }

    /**
     * @reason add player to the appropriate chunk asynchronously instead of blocking
     * @author gegy1000
     */
    @Redirect(
            method = "addPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;"
            )
    )
    private Chunk addPlayer(ServerWorld world, int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create, ServerPlayerEntity player) {
        this.getChunkAsync(chunkX, chunkZ, leastStatus).thenAccept(chunk -> {
            if (chunk instanceof WorldChunk) {
                chunk.addEntity(player);
            }
        });

        return null;
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
    public BlockState getBlockStateIfReady(BlockPos pos) {
        if (isHeightInvalid(pos)) {
            return Blocks.VOID_AIR.getDefaultState();
        }

        Chunk chunk = this.serverChunkManager.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FEATURES, false);
        if (chunk != null) {
            return chunk.getBlockState(pos);
        } else {
            return Blocks.AIR.getDefaultState();
        }
    }

    @Override
    public FluidState getFluidStateIfReady(BlockPos pos) {
        if (isHeightInvalid(pos)) {
            return Fluids.EMPTY.getDefaultState();
        }

        Chunk chunk = this.serverChunkManager.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FEATURES, false);
        if (chunk != null) {
            return chunk.getFluidState(pos);
        } else {
            return Fluids.EMPTY.getDefaultState();
        }
    }

    @Override
    public boolean isChunkReady(int x, int z) {
        return this.serverChunkManager.getChunk(x, z, ChunkStatus.FULL, false) != null;
    }

    @Override
    public CompletableFuture<Chunk> getChunkAsync(int x, int z, ChunkStatus status) {
        return ((AsyncChunkAccess) this.serverChunkManager).getChunkAsync(x, z, status);
    }
}
