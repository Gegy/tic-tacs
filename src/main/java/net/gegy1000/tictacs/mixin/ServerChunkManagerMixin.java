package net.gegy1000.tictacs.mixin;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Mixin(ServerChunkManager.class)
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

            this.spawnEntry = spawnMobs ? this.setupSpawnInfo() : null;
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

    private void tickChunks(long timeSinceSpawn, SpawnHelper.Info spawnInfo) {
        ChunkController controller = (ChunkController) this.threadedAnvilChunkStorage;

        List<ChunkEntry> tickingChunks = this.collectTickingChunks(controller);
        if (tickingChunks.isEmpty()) {
            return;
        }

        Collections.shuffle(tickingChunks);
        this.tickChunks(timeSinceSpawn, spawnInfo, tickingChunks);

        this.tickingChunks.clear();
    }

    private void tickChunks(long timeSinceSpawn, SpawnHelper.Info spawnInfo, List<ChunkEntry> chunks) {
        Profiler profiler = this.world.getProfiler();

        boolean spawnAnimals = this.world.getTime() % 400 == 0L;
        int tickSpeed = this.world.getGameRules().getInt(GameRules.RANDOM_TICK_SPEED);

        ChunkController controller = (ChunkController) this.threadedAnvilChunkStorage;

        for (ChunkEntry entry : chunks) {
            WorldChunk worldChunk = entry.getWorldChunk();
            if (worldChunk == null) {
                continue;
            }

            profiler.push("broadcast");
            entry.flushUpdates(worldChunk);
            profiler.pop();

            if (entry.isTickingEntities()) {
                if (controller.isTooFarFromPlayersToSpawnMobs(entry)) {
                    continue;
                }

                worldChunk.setInhabitedTime(worldChunk.getInhabitedTime() + timeSinceSpawn);

                if (spawnInfo != null && this.world.getWorldBorder().contains(entry.getPos())) {
                    SpawnHelper.spawn(this.world, worldChunk, spawnInfo, this.spawnAnimals, this.spawnMonsters, spawnAnimals);
                }

                this.world.tickChunk(worldChunk, tickSpeed);
            }
        }
    }

    private SpawnHelper.Info setupSpawnInfo() {
        Profiler profiler = this.world.getProfiler();
        profiler.push("naturalSpawnCount");

        int spawnChunkCount = this.ticketManager.getSpawningChunkCount();
        SpawnHelper.Info spawnInfo = SpawnHelper.setupSpawn(spawnChunkCount, this.world.iterateEntities(), this::ifChunkLoaded);

        profiler.pop();

        return spawnInfo;
    }

    private List<ChunkEntry> collectTickingChunks(ChunkController controller) {
        ObjectCollection<ChunkEntry> entries = controller.getMap().primary().getEntries();

        List<ChunkEntry> tickingChunks = this.tickingChunks;
        tickingChunks.clear();

        for (ChunkEntry entry : entries) {
            if (entry.isTicking()) {
                tickingChunks.add(entry);
            }
        }

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
