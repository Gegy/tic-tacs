package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.AsyncChunkAccess;
import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.Heightmap;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin extends World implements NonBlockingWorldAccess, AsyncChunkAccess {
    @Shadow
    @Final
    private ServerChunkManager serverChunkManager;

    private ServerWorldMixin(MutableWorldProperties properties, RegistryKey<World> registryKey, DimensionType dimensionType, Supplier<Profiler> supplier, boolean client, boolean debugWorld, long biomeSeed) {
        super(properties, registryKey, dimensionType, supplier, client, debugWorld, biomeSeed);
    }

    @Override
    public BlockState getBlockStateIfLoaded(BlockPos pos) {
        if (isOutOfBuildLimitVertically(pos)) {
            return Blocks.VOID_AIR.getDefaultState();
        }

        Chunk chunk = this.getExistingChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStep.FEATURES);
        if (chunk != null) {
            return chunk.getBlockState(pos);
        } else {
            return Blocks.AIR.getDefaultState();
        }
    }

    @Override
    public FluidState getFluidStateIfLoaded(BlockPos pos) {
        if (isOutOfBuildLimitVertically(pos)) {
            return Fluids.EMPTY.getDefaultState();
        }

        Chunk chunk = this.getExistingChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStep.FEATURES);
        if (chunk != null) {
            return chunk.getFluidState(pos);
        } else {
            return Fluids.EMPTY.getDefaultState();
        }
    }

    @Override
    public int getTopY(Heightmap.Type heightmap, int x, int z) {
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) {
            return this.getSeaLevel() + 1;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (this.shouldChunkExist(chunkX, chunkZ, ChunkStep.FEATURES)) {
            Chunk chunk = this.getChunk(chunkX, chunkZ, ChunkStatus.FEATURES);
            return chunk.sampleHeightmap(heightmap, x & 15, z & 15) + 1;
        } else {
            return 0;
        }
    }

    @Override
    public boolean isChunkLoaded(int x, int z) {
        return this.getExistingChunk(x, z, ChunkStep.FULL) != null;
    }

    @Override
    public Chunk getExistingChunk(int x, int z, ChunkStep step) {
        return ((AsyncChunkAccess) this.serverChunkManager).getExistingChunk(x, z, step);
    }

    @Nullable
    @Override
    public Chunk getAnyExistingChunk(int chunkX, int chunkZ) {
        return ((AsyncChunkAccess) this.serverChunkManager).getAnyExistingChunk(chunkX, chunkZ);
    }

    @Override
    public CompletableFuture<Chunk> getOrCreateChunkAsync(int x, int z, ChunkStep step) {
        return ((AsyncChunkAccess) this.serverChunkManager).getOrCreateChunkAsync(x, z, step);
    }

    @Override
    public boolean shouldChunkExist(int x, int z, ChunkStep step) {
        return ((AsyncChunkAccess) this.serverChunkManager).shouldChunkExist(x, z, step);
    }
}
