package net.gegy1000.tictacs.mixin.io;

import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.AsyncChunkIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.StorageIoWorker;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Mixin(StorageIoWorker.class)
public abstract class StorageIoWorkerMixin implements AsyncChunkIo {
    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private RegionBasedStorage storage;

    @Shadow
    @Final
    private Map<ChunkPos, StorageIoWorker.Result> results;

    @Shadow
    protected abstract <T> CompletableFuture<T> run(Supplier<Either<T, Exception>> supplier);

    @Override
    public CompletableFuture<CompoundTag> getNbtAsync(ChunkPos pos) {
        return this.run(() -> {
            StorageIoWorker.Result result = this.results.get(pos);
            if (result == null) {
                return this.loadNbtAt(pos);
            }
            return Either.left(result.nbt);
        });
    }

    public Either<CompoundTag, Exception> loadNbtAt(ChunkPos pos) {
        try {
            CompoundTag compoundTag = this.storage.getTagAt(pos);
            return Either.left(compoundTag);
        } catch (Exception e) {
            LOGGER.warn("Failed to read chunk {}", pos, e);
            return Either.right(e);
        }
    }
}
