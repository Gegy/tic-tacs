package net.gegy1000.tictacs.mixin.client;

import net.gegy1000.tictacs.NonBlockingWorldAccess;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Supplier;

@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin extends World implements NonBlockingWorldAccess {
    private ClientWorldMixin(MutableWorldProperties properties, RegistryKey<World> registryKey, DimensionType dimensionType, Supplier<Profiler> supplier, boolean client, boolean debugWorld, long biomeSeed) {
        super(properties, registryKey, dimensionType, supplier, client, debugWorld, biomeSeed);
    }

    @Override
    public boolean isChunkReady(int x, int z) {
        return this.isChunkLoaded(x, z);
    }
}
