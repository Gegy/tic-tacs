package net.gegy1000.tictacs.mixin.unblocking;

import net.gegy1000.tictacs.NonBlockingChunkAccess;
import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Supplier;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin extends World implements NonBlockingWorldAccess, NonBlockingChunkAccess {
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

        Chunk chunk = this.getExistingChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
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

        Chunk chunk = this.getExistingChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
        if (chunk != null) {
            return chunk.getFluidState(pos);
        } else {
            return Fluids.EMPTY.getDefaultState();
        }
    }

    @Override
    public Chunk getExistingChunk(int x, int z, ChunkStatus status) {
        return ((NonBlockingChunkAccess) this.serverChunkManager).getExistingChunk(x, z, status);
    }

    @Override
    public boolean doesChunkExist(int x, int z, ChunkStatus status) {
        return ((NonBlockingChunkAccess) this.serverChunkManager).doesChunkExist(x, z, status);
    }
}
