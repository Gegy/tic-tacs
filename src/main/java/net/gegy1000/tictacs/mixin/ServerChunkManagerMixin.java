package net.gegy1000.tictacs.mixin;

import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.GameRules;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

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

    private final List<ChunkEntry> tickingChunks = new ArrayList<>();

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

        for (ChunkEntry entry : chunks) {
            WorldChunk worldChunk = entry.getWorldChunk();
            if (worldChunk == null) {
                continue;
            }

            profiler.push("broadcast");
            entry.flushUpdates(worldChunk);
            profiler.pop();

            if (entry.isTickingEntities()) {
                ChunkPos chunkPos = entry.getPos();
                if (this.threadedAnvilChunkStorage.isTooFarFromPlayersToSpawnMobs(chunkPos)) {
                    continue;
                }

                worldChunk.setInhabitedTime(worldChunk.getInhabitedTime() + timeSinceSpawn);

                if (spawnInfo != null && this.world.getWorldBorder().contains(chunkPos)) {
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

    @Shadow
    protected abstract void ifChunkLoaded(long pos, Consumer<WorldChunk> chunkConsumer);
}
