package net.gegy1000.tictacs.mixin.io;

import com.mojang.serialization.DynamicOps;
import net.gegy1000.tictacs.AsyncChunkIo;
import net.gegy1000.tictacs.AsyncRegionStorageIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.SerializingRegionBasedStorage;
import net.minecraft.world.storage.StorageIoWorker;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(SerializingRegionBasedStorage.class)
public abstract class SerializingRegionBasedStorageMixin implements AsyncRegionStorageIo, AsyncChunkIo {
    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private StorageIoWorker worker;

    @Shadow
    protected abstract <T> void update(ChunkPos pos, DynamicOps<T> dynamicOps, @Nullable T data);

    @Override
    public CompletableFuture<Void> loadDataAtAsync(ChunkPos pos, Executor mainThreadExecutor) {
        return this.getNbtAsync(pos).thenAcceptAsync(tag -> {
            this.update(pos, NbtOps.INSTANCE, tag);
        }, mainThreadExecutor);
    }

    @Override
    public CompletableFuture<CompoundTag> getNbtAsync(ChunkPos pos) {
        return ((AsyncChunkIo) this.worker).getNbtAsync(pos).handle((tag, throwable) -> {
            if (throwable != null) {
                LOGGER.error("Error reading chunk {} data from disk", pos, throwable);
                return null;
            }
            return tag;
        });
    }
}
