package net.gegy1000.acttwo.chunk.loader;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureManager;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class ChunkStorage extends VersionedChunkStorage implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger(ChunkStorage.class);

    private final ServerWorld world;
    private final PointOfInterestStorage poiStorage;

    private final Supplier<PersistentStateManager> persistentStateSupplier;

    private ChunkStorage(ServerWorld world, File regionRoot, DataFixer dataFixer, PointOfInterestStorage poiStorage, boolean sync) {
        super(regionRoot, dataFixer, sync);
        this.world = world;
        this.poiStorage = poiStorage;

        this.persistentStateSupplier = () -> this.world.getServer().getOverworld().getPersistentStateManager();
    }

    public static ChunkStorage open(ServerWorld world, LevelStorage.Session storageSession) {
        File saveRoot = storageSession.method_27424(world.getRegistryKey());
        File regionRoot = new File(saveRoot, "region");
        File poiRoot = new File(saveRoot, "poi");

        MinecraftServer server = world.getServer();
        DataFixer dataFixer = server.getDataFixer();
        boolean syncWrites = server.syncChunkWrites();

        PointOfInterestStorage poiStorage = new PointOfInterestStorage(poiRoot, dataFixer, syncWrites);

        return new ChunkStorage(world, regionRoot, dataFixer, poiStorage, syncWrites);
    }

    @Nullable
    private CompoundTag loadAndUpdateChunkTag(ChunkPos pos) throws IOException {
        CompoundTag chunkTag = this.getNbt(pos);
        if (chunkTag == null) {
            return null;
        }

        return this.updateChunkTag(this.world.getRegistryKey(), this.persistentStateSupplier, chunkTag);
    }

    public Chunk loadChunk(ChunkPos pos) {
        try {
            CompoundTag root = this.loadAndUpdateChunkTag(pos);
            if (this.validateChunkTag(root)) {
                return this.deserializeChunk(pos, root);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load chunk at {}", pos, e);
        }

        return new ProtoChunk(pos, UpgradeData.NO_UPGRADE_DATA);
    }

    private Chunk deserializeChunk(ChunkPos pos, CompoundTag root) {
        StructureManager structureManager = this.world.getStructureManager();
        PointOfInterestStorage poiStorage = this.world.getPointOfInterestStorage();

        Chunk chunk = ChunkSerializer.deserialize(this.world, structureManager, poiStorage, pos, root);
        chunk.setLastSaveTime(this.world.getTime());

        return chunk;
    }

    private boolean validateChunkTag(CompoundTag tag) {
        if (tag == null) {
            return false;
        }

        return tag.getCompound("Level").contains("Status", 8);
    }

    public boolean saveChunk(Chunk chunk) {
        this.poiStorage.method_20436(chunk.getPos());
        if (!chunk.needsSaving()) {
            return false;
        }

        chunk.setLastSaveTime(this.world.getTime());
        chunk.setShouldSave(false);

        if (this.isChunkEmpty(chunk)) {
            return false;
        }

        this.setTagAt(chunk.getPos(), ChunkSerializer.serialize(this.world, chunk));

        return true;
    }

    private boolean isChunkEmpty(Chunk chunk) {
        ChunkStatus status = chunk.getStatus();
        if (status == ChunkStatus.EMPTY) {
            return chunk.getStructureStarts().values().stream().noneMatch(StructureStart::hasChildren);
        }
        return false;
    }

    public void tick(BooleanSupplier runWhile) {
        Profiler profiler = this.world.getProfiler();
        profiler.push("poi");
        this.poiStorage.tick(runWhile);
        profiler.pop();
    }

    @Override
    public void close() throws IOException {
        this.poiStorage.close();
    }
}
