package net.gegy1000.tictacs.mixin.chunk_ticking;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.GameRules;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Mixin(value = ServerChunkManager.class, priority = 999)
public abstract class ServerChunkManagerMixin {
    @Shadow
    @Final
    public ThreadedAnvilChunkStorage threadedAnvilChunkStorage;
    @Shadow
    @Final
    private ServerWorld world;
    @Shadow
    private long lastMobSpawningTime;
    @Shadow
    @Nullable
    private SpawnHelper.Info spawnEntry;
    @Shadow
    @Final
    private ChunkTicketManager ticketManager;
    @Shadow
    private boolean spawnAnimals;
    @Shadow
    private boolean spawnMonsters;

    private ChunkAccess primaryChunks;
    private final List<ChunkEntry> tickingChunks = new ArrayList<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(ServerWorld world, LevelStorage.Session session, DataFixer dataFixer, StructureManager structureManager, Executor workerExecutor, ChunkGenerator chunkGenerator, int viewDistance, boolean bl, WorldGenerationProgressListener worldGenerationProgressListener, Supplier<PersistentStateManager> supplier, CallbackInfo ci) {
        this.primaryChunks = ((ChunkController) this.threadedAnvilChunkStorage).getMap().primary();
    }

    /**
     * @reason optimize chunk ticking and iteration logic
     * @author gegy1000
     */
    @Overwrite
    private void tickChunks() {
        long time = this.world.getTime();

        long timeSinceSpawn = time - this.lastMobSpawningTime;
        this.lastMobSpawningTime = time;

        boolean doMobSpawning = this.world.getGameRules().getBoolean(GameRules.DO_MOB_SPAWNING);
        boolean spawnMobs = doMobSpawning && (this.spawnMonsters || this.spawnAnimals);

        if (!this.world.isDebugWorld()) {
            Profiler profiler = this.world.getProfiler();
            profiler.push("pollingChunks");

            this.flushChunkUpgrades();

            this.spawnEntry = spawnMobs ? this.setupSpawnInfo(this.ticketManager.getSpawningChunkCount()) : null;
            this.tickChunks(timeSinceSpawn, this.spawnEntry);

            if (doMobSpawning) {
                profiler.push("customSpawners");
                this.world.tickSpawners(this.spawnMonsters, this.spawnAnimals);
                profiler.pop();
            }

            profiler.pop();
        }

        this.threadedAnvilChunkStorage.tickPlayerMovement();
    }

    private void flushChunkUpgrades() {
        ChunkController controller = (ChunkController) this.threadedAnvilChunkStorage;
        Collection<ChunkEntry> trackableChunks = controller.getMap().getTickingMaps().getTrackableEntries();

        Profiler profiler = this.world.getProfiler();
        profiler.push("broadcast");

        for (ChunkEntry entry : trackableChunks) {
            WorldChunk worldChunk = entry.getWorldChunk();
            if (worldChunk != null) {
                entry.flushUpdates(worldChunk);
            }
        }

        profiler.pop();
    }

    private void tickChunks(long timeSinceSpawn, SpawnHelper.Info spawnInfo) {
        ChunkController controller = (ChunkController) this.threadedAnvilChunkStorage;

        List<ChunkEntry> tickingChunks = this.collectTickingChunks(controller);
        if (!tickingChunks.isEmpty()) {
            this.tickChunks(timeSinceSpawn, spawnInfo, tickingChunks);
        }

        this.tickingChunks.clear();
    }

    private void tickChunks(long timeSinceSpawn, SpawnHelper.Info spawnInfo, List<ChunkEntry> chunks) {
        boolean spawnAnimals = this.world.getTime() % 400 == 0L;
        int tickSpeed = this.world.getGameRules().getInt(GameRules.RANDOM_TICK_SPEED);

        for (ChunkEntry entry : chunks) {
            WorldChunk worldChunk = entry.getWorldChunk();

            if (worldChunk != null && entry.isChunkTickable()) {
                worldChunk.setInhabitedTime(worldChunk.getInhabitedTime() + timeSinceSpawn);

                if (spawnInfo != null && this.world.getWorldBorder().contains(entry.getPos())) {
                    SpawnHelper.spawn(this.world, worldChunk, spawnInfo, this.spawnAnimals, this.spawnMonsters, spawnAnimals);
                }

                this.world.tickChunk(worldChunk, tickSpeed);
            }
        }
    }

    private SpawnHelper.Info setupSpawnInfo(int spawnChunkCount) {
        Profiler profiler = this.world.getProfiler();
        profiler.push("naturalSpawnCount");

        SpawnHelper.Info spawnInfo = SpawnHelper.setupSpawn(spawnChunkCount, this.world.iterateEntities(), this::ifChunkLoaded);

        profiler.pop();

        return spawnInfo;
    }

    private List<ChunkEntry> collectTickingChunks(ChunkController controller) {
        Collection<ChunkEntry> entries = controller.getMap().getTickingMaps().getTickableEntries();
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChunkEntry> tickingChunks = this.tickingChunks;
        tickingChunks.clear();
        tickingChunks.addAll(entries);

        Collections.shuffle(tickingChunks);

        return tickingChunks;
    }

    @Unique
    private void ifChunkLoaded(long pos, Consumer<WorldChunk> consumer) {
        ChunkEntry entry = this.primaryChunks.getEntry(pos);
        if (entry != null) {
            Either<WorldChunk, ChunkHolder.Unloaded> accessible = entry.getAccessibleFuture().getNow(null);
            if (accessible == null) {
                return;
            }

            accessible.ifLeft(consumer);
        }
    }
}
