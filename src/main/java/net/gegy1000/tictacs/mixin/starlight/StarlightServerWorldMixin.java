package net.gegy1000.tictacs.mixin.starlight;

import ca.spottedleaf.starlight.common.world.ExtendedWorld;
import net.gegy1000.tictacs.AsyncChunkAccess;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Supplier;

@Mixin(value = ServerWorld.class, priority = 1001)
public abstract class StarlightServerWorldMixin extends World implements ExtendedWorld {
    @Shadow
    @Final
    private ServerChunkManager serverChunkManager;

    private StarlightServerWorldMixin(MutableWorldProperties properties, RegistryKey<World> registryRef, DimensionType dimensionType, Supplier<Profiler> profiler, boolean isClient, boolean debugWorld, long seed) {
        super(properties, registryRef, dimensionType, profiler, isClient, debugWorld, seed);
    }

    // these implementations are not strictly necessary, but they optimize starlight's chunk queries
    @Override
    public WorldChunk getChunkAtImmediately(int x, int z) {
        return this.serverChunkManager.getWorldChunk(x, z);
    }

    @Override
    public Chunk getAnyChunkImmediately(int x, int z) {
        return ((AsyncChunkAccess) this.serverChunkManager).getAnyExistingChunk(x, z);
    }
}
