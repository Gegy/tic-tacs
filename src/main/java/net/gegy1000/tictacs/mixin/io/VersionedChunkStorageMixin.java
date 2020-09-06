package net.gegy1000.tictacs.mixin.io;

import net.gegy1000.tictacs.AsyncChunkIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.StorageIoWorker;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.CompletableFuture;

@Mixin(VersionedChunkStorage.class)
public class VersionedChunkStorageMixin implements AsyncChunkIo {
    @Shadow
    @Final
    private StorageIoWorker worker;

    @Override
    public CompletableFuture<CompoundTag> getNbtAsync(ChunkPos pos) {
        return ((AsyncChunkIo) this.worker).getNbtAsync(pos);
    }
}
