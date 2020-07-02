package net.gegy1000.acttwo.chunk;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.future.GetChunkContext;
import net.gegy1000.acttwo.chunk.future.UpgradeChunk;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.concurrent.CompletableFuture;

public interface TacsExt {
    Future<Chunk> getChunk(int chunkX, int chunkZ, ChunkStatus targetStatus);

    Future<Chunk> getChunk(ChunkHolder holder, ChunkStatus targetStatus);

    GetChunkContext getChunkContext(ChunkPos pos, ChunkStatus[] statuses);

    UpgradeChunk upgradeChunk(ChunkHolder holder, ChunkStatus currentStatus, ChunkStatus targetStatus);

    Future<Chunk> spawnLoadChunk(ChunkHolder holder);

    void spawnUpgradeFrom(Future<Chunk> fromFuture, ChunkHolder holder, ChunkStatus fromStatus, ChunkStatus toStatus);

    CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> accessConvertToFullChunk(ChunkHolder holder);
}
