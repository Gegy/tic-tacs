package net.gegy1000.acttwo.chunk.step;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.chunk.future.VanillaChunkFuture;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ChunkStep {
    private static final EnumSet<Heightmap.Type> REQUIRED_FEATURE_HEIGHTMAPS = EnumSet.of(
            Heightmap.Type.MOTION_BLOCKING,
            Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Type.OCEAN_FLOOR,
            Heightmap.Type.WORLD_SURFACE
    );

    public static final List<ChunkStep> STEPS = new ArrayList<>();

    public static final ChunkStep EMPTY = new ChunkStep("empty")
            .includes(ChunkStatus.EMPTY)
            .requires(ChunkRequirements.none());

    public static final ChunkStep STRUCTURE_STARTS = new ChunkStep("structure_starts")
            .includes(ChunkStatus.STRUCTURE_STARTS)
            .requires(ChunkRequirements.from(ChunkStep.EMPTY))
            .runSync(ChunkStep::addStructureStarts);

    public static final ChunkStep SURFACE = new ChunkStep("surface")
            .includes(
                    ChunkStatus.STRUCTURE_REFERENCES, ChunkStatus.BIOMES,
                    ChunkStatus.NOISE, ChunkStatus.SURFACE,
                    ChunkStatus.CARVERS, ChunkStatus.LIQUID_CARVERS
            )
            .requires(
                    ChunkRequirements.from(ChunkStep.STRUCTURE_STARTS)
                            .read(ChunkStep.STRUCTURE_STARTS, 8)
            )
            .runSync(ChunkStep::generateSurface);

    public static final ChunkStep FEATURES = new ChunkStep("features")
            .includes(ChunkStatus.FEATURES)
            .requires(
                    ChunkRequirements.from(ChunkStep.SURFACE)
                            .write(ChunkStep.SURFACE, 1)
                            .read(ChunkStep.STRUCTURE_STARTS, 8)
            )
            .runSync(ChunkStep::addFeatures);

    public static final ChunkStep LIGHTING = new ChunkStep("lighting")
            .includes(ChunkStatus.LIGHT)
            .requires(
                    ChunkRequirements.from(ChunkStep.FEATURES)
                            .read(ChunkStep.FEATURES, 1)
            )
            .runAsync(ChunkStep::lightChunk);

    public static final ChunkStep FULL = new ChunkStep("full")
            .includes(ChunkStatus.SPAWN, ChunkStatus.HEIGHTMAPS, ChunkStatus.FULL)
            .requires(ChunkRequirements.from(ChunkStep.LIGHTING))
            .runSync(ChunkStep::addEntities);

    private static final ChunkStep[] STATUS_TO_STEP;

    private static final int[] STEP_TO_RADIUS;

    private static final int MAX_DISTANCE;
    private static final int[] STEP_TO_DISTANCE;
    private static final ChunkStep[] DISTANCE_TO_STEP;

    private final int index;
    private final String name;
    private ChunkStatus[] statuses = new ChunkStatus[0];
    private AsyncTask task = AsyncTask.noop();
    private ChunkRequirements requirements = ChunkRequirements.none();

    ChunkStep(String name) {
        int index = STEPS.size();
        STEPS.add(this);

        this.index = index;
        this.name = name;
    }

    ChunkStep requires(ChunkRequirements requirements) {
        this.requirements = requirements;
        return this;
    }

    ChunkStep includes(ChunkStatus... statuses) {
        this.statuses = statuses;
        return this;
    }

    ChunkStep runAsync(AsyncTask task) {
        this.task = task;
        return this;
    }

    ChunkStep runSync(SyncTask task) {
        this.task = AsyncTask.from(task);
        return this;
    }

    public Future<Chunk> run(ChunkStepContext ctx) {
        return this.task.run(ctx);
    }

    public ChunkRequirements getRequirements() {
        return this.requirements;
    }

    public ChunkStatus getMaximumStatus() {
        return this.statuses[this.statuses.length - 1];
    }

    public int getIndex() {
        return this.index;
    }

    public boolean greaterOrEqual(ChunkStep step) {
        return this.index >= step.index;
    }

    public boolean lessOrEqual(ChunkStep step) {
        return this.index <= step.index;
    }

    public boolean greaterThan(ChunkStep step) {
        return this.index > step.index;
    }

    public boolean lessThan(ChunkStep step) {
        return this.index < step.index;
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Nullable
    public ChunkStep getPrevious() {
        return byIndex(this.index - 1);
    }

    @Nullable
    public ChunkStep getNext() {
        return byIndex(this.index + 1);
    }

    public static ChunkStep min(ChunkStep a, ChunkStep b) {
        if (a == null || b == null) return null;

        return a.index < b.index ? a : b;
    }

    public static ChunkStep max(ChunkStep a, ChunkStep b) {
        if (a == null) return b;
        if (b == null) return a;

        return a.index > b.index ? a : b;
    }

    @Nullable
    public static ChunkStep byIndex(int index) {
        if (index < 0 || index >= STEPS.size()) {
            return null;
        }
        return STEPS.get(index);
    }

    public static ChunkStep byStatus(ChunkStatus status) {
        return STATUS_TO_STEP[status.getIndex()];
    }

    public static int getRequiredRadius(ChunkStep step) {
        return STEP_TO_RADIUS[step.getIndex()];
    }

    public static int getDistanceFromFull(ChunkStep step) {
        return STEP_TO_DISTANCE[step.getIndex()];
    }

    public static ChunkStep byDistanceFromFull(int distance) {
        if (distance < 0) return FULL;
        if (distance >= DISTANCE_TO_STEP.length) return EMPTY;

        return DISTANCE_TO_STEP[distance];
    }

    public static int getMaxDistance() {
        return MAX_DISTANCE;
    }

    static {
        // initialize status -> step mapping
        List<ChunkStatus> statuses = ChunkStatus.createOrderedList();
        STATUS_TO_STEP = new ChunkStep[statuses.size()];

        for (ChunkStep step : STEPS) {
            for (ChunkStatus status : step.statuses) {
                STATUS_TO_STEP[status.getIndex()] = step;
            }
        }

        StepKernelResolver.Results results = new StepKernelResolver(STEPS).resolve();

        MAX_DISTANCE = results.maxDistance;
        STEP_TO_RADIUS = results.stepToRadius;
        DISTANCE_TO_STEP = results.distanceToStep;
        STEP_TO_DISTANCE = results.stepToDistance;
    }

    private static Chunk addStructureStarts(ChunkStepContext ctx) {
        ServerWorld world = ctx.world;
        GeneratorOptions options = world.getServer().getSaveProperties().getGeneratorOptions();
        if (options.shouldGenerateStructures()) {
            ctx.generator.setStructureStarts(world.getStructureAccessor(), ctx.chunk, ctx.structures, world.getSeed());
        }
        return ctx.chunk;
    }

    private static Chunk generateSurface(ChunkStepContext ctx) {
        ChunkGenerator generator = ctx.generator;
        ServerWorld world = ctx.world;
        Chunk chunk = ctx.chunk;

        ChunkRegion region = ctx.asRegion();
        StructureAccessor structureAccessor = ctx.asStructureAccessor();

        generator.addStructureReferences(region, structureAccessor, chunk);
        trySetStatus(chunk, ChunkStatus.STRUCTURE_REFERENCES);

        generator.populateBiomes(chunk);
        trySetStatus(chunk, ChunkStatus.BIOMES);

        generator.populateNoise(region, structureAccessor, chunk);
        trySetStatus(chunk, ChunkStatus.NOISE);

        generator.buildSurface(region, chunk);
        trySetStatus(chunk, ChunkStatus.SURFACE);

        generator.carve(world.getSeed(), world.getBiomeAccess(), chunk, GenerationStep.Carver.AIR);
        trySetStatus(chunk, ChunkStatus.CARVERS);

        generator.carve(world.getSeed(), world.getBiomeAccess(), chunk, GenerationStep.Carver.LIQUID);
        trySetStatus(chunk, ChunkStatus.LIQUID_CARVERS);

        return chunk;
    }

    private static Chunk addFeatures(ChunkStepContext ctx) {
        ProtoChunk proto = (ProtoChunk) ctx.chunk;
        proto.setLightingProvider(ctx.lighting);

        Heightmap.populateHeightmaps(ctx.chunk, REQUIRED_FEATURE_HEIGHTMAPS);

        ChunkRegion region = ctx.asRegion();
        ctx.generator.generateFeatures(region, ctx.world.getStructureAccessor().method_29951(region));

        return ctx.chunk;
    }

    private static Future<Chunk> lightChunk(ChunkStepContext ctx) {
        ServerLightingProvider lighting = ctx.lighting;
        CompletableFuture<Chunk> future = lighting.light(ctx.chunk, false)
                .thenCompose(chunk -> lighting.light(chunk, true));

        return VanillaChunkFuture.of(future.thenApply(Either::left));
    }

    private static Chunk addEntities(ChunkStepContext ctx) {
        ChunkRegion region = ctx.asRegion();
        ctx.generator.populateEntities(region);

        return ctx.chunk;
    }

    private static void trySetStatus(Chunk chunk, ChunkStatus status) {
        if (chunk instanceof ProtoChunk) {
            ((ProtoChunk) chunk).setStatus(status);
        }
    }

    public interface AsyncTask {
        static AsyncTask noop() {
            return ctx -> Future.ready(ctx.chunk);
        }

        static AsyncTask from(SyncTask task) {
            return ctx -> Future.ready(task.run(ctx));
        }

        Future<Chunk> run(ChunkStepContext ctx);
    }

    public interface SyncTask {
        static SyncTask noop() {
            return ctx -> ctx.chunk;
        }

        Chunk run(ChunkStepContext ctx);
    }
}
