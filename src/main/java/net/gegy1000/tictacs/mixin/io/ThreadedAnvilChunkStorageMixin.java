package net.gegy1000.tictacs.mixin.io;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.util.NbtType;
import net.gegy1000.tictacs.AsyncChunkIo;
import net.gegy1000.tictacs.AsyncRegionStorageIo;
import net.gegy1000.tictacs.chunk.io.ChunkData;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.Util;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import org.jetbrains.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageMixin extends VersionedChunkStorage implements AsyncChunkIo {
    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private ServerWorld world;
    @Shadow
    @Final
    private PointOfInterestStorage pointOfInterestStorage;
    @Shadow
    @Final
    private StructureManager structureManager;
    @Shadow
    @Final
    private Supplier<PersistentStateManager> persistentStateManagerFactory;
    @Shadow
    @Final
    private ThreadExecutor<Runnable> mainThreadExecutor;

    private ThreadedAnvilChunkStorageMixin(File file, DataFixer dataFixer, boolean dsync) {
        super(file, dataFixer, dsync);
    }

    /**
     * @reason avoid blocking the main thread to load chunk
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> loadChunk(ChunkPos pos) {
        return this.getUpdatedChunkTagAsync(pos)
                .thenApplyAsync(tag -> this.deserializeChunkData(pos, tag), Util.getMainWorkerExecutor())
                .handleAsync((data, throwable) -> {
                    if (throwable != null) {
                        if (throwable instanceof CompletionException) {
                            throwable = throwable.getCause();
                        }
                        LOGGER.error("Couldn't load chunk {}", pos, throwable);
                    }

                    return this.createChunkFromData(pos, data);
                }, this.mainThreadExecutor);
    }

    private Either<Chunk, ChunkHolder.Unloaded> createChunkFromData(ChunkPos pos, ChunkData data) {
        if (data != null) {
            this.world.getProfiler().visit("chunkLoad");

            try {
                Chunk chunk = data.createChunk(this.world, this.structureManager, this.pointOfInterestStorage);
                chunk.setLastSaveTime(this.world.getTime());

                this.method_27053(pos, chunk.getStatus().getChunkType());
                return Either.left(chunk);
            } catch (CrashException crash) {
                Throwable cause = crash.getCause();
                if (!(cause instanceof IOException)) {
                    this.method_27054(pos);
                    throw crash;
                }

                LOGGER.error("Couldn't load chunk {}", pos, crash);
            } catch (Exception e) {
                LOGGER.error("Couldn't load chunk {}", pos, e);
            }
        }

        this.method_27054(pos);
        return Either.left(new ProtoChunk(pos, UpgradeData.NO_UPGRADE_DATA));
    }

    @Nullable
    private ChunkData deserializeChunkData(ChunkPos pos, CompoundTag tag) {
        if (tag == null) {
            return null;
        }

        if (!tag.contains("Level", NbtType.COMPOUND) || !tag.getCompound("Level").contains("Status", NbtType.STRING)) {
            LOGGER.error("Chunk file at {} is missing level data, skipping", pos);
            return null;
        }

        return ChunkData.deserialize(pos, tag);
    }

    private CompletableFuture<CompoundTag> getUpdatedChunkTagAsync(ChunkPos pos) {
        CompletableFuture<CompoundTag> chunkTag = this.getNbtAsync(pos)
                .thenCompose(tag -> {
                    if (tag != null && getDataVersion(tag) < SharedConstants.getGameVersion().getWorldVersion()) {
                        // TODO: ideally we don't need to datafix chunks on the main thread
                        return CompletableFuture.supplyAsync(() -> {
                            return this.updateChunkTag(this.world.getRegistryKey(), this.persistentStateManagerFactory, tag);
                        }, this.mainThreadExecutor);
                    }

                    return CompletableFuture.completedFuture(tag);
                });

        CompletableFuture<Void> loadPoi = ((AsyncRegionStorageIo) this.pointOfInterestStorage).loadDataAtAsync(pos, this.mainThreadExecutor);

        return chunkTag.thenCombine(loadPoi, (tag, v) -> tag);
    }

    @Shadow
    protected abstract void method_27054(ChunkPos chunkPos);

    @Shadow
    protected abstract byte method_27053(ChunkPos chunkPos, ChunkStatus.ChunkType chunkType);
}
