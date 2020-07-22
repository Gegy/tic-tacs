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

    private static final EnumSet<Heightmap.Type> WG_HEIGHTMAPS = EnumSet.of(
            Heightmap.Type.OCEAN_FLOOR_WG,
            Heightmap.Type.WORLD_SURFACE_WG
    );

    public static final ChunkStep EMPTY = new ChunkStep("empty")
            .includes(ChunkStatus.EMPTY)
            .margin(ChunkMargin.none());

    public static final ChunkStep STRUCTURE_STARTS = new ChunkStep("structure_starts")
            .includes(ChunkStatus.STRUCTURE_STARTS)
            .margin(ChunkMargin.none())
            .runSync(ChunkStep::addStructureStarts);

    public static final ChunkStep SURFACE = new ChunkStep("surface")
            .includes(
                    ChunkStatus.STRUCTURE_REFERENCES, ChunkStatus.BIOMES,
                    ChunkStatus.NOISE, ChunkStatus.SURFACE,
                    ChunkStatus.CARVERS, ChunkStatus.LIQUID_CARVERS
            )
            .margin(ChunkMargin.read(8))
            .runSync(ChunkStep::generateSurface);

    // TODO: huge feature radius
    public static final ChunkStep FEATURES = new ChunkStep("features")
            .includes(ChunkStatus.FEATURES)
            .margin(ChunkMargin.write(8))
            .runSync(ChunkStep::addFeatures);

    public static final ChunkStep LIGHTING = new ChunkStep("lighting")
            .includes(ChunkStatus.LIGHT)
            .margin(ChunkMargin.read(1))
            .runAsync(ChunkStep::lightChunk);

    public static final ChunkStep FULL = new ChunkStep("full")
            .includes(ChunkStatus.SPAWN, ChunkStatus.HEIGHTMAPS, ChunkStatus.FULL)
            .margin(ChunkMargin.none())
            .runSync(ChunkStep::addEntities);

    public static final ChunkStep[] STEPS = { EMPTY, STRUCTURE_STARTS, SURFACE, FEATURES, LIGHTING, FULL };

    private static final ChunkStep[] STATUS_TO_STEP;

    private static final int[] STEP_TO_RADIUS;

    private static final int MAX_DISTANCE;
    private static final int[] STEP_TO_DISTANCE;
    private static final ChunkStep[] DISTANCE_TO_STEP;

    private final String name;
    private ChunkStatus[] statuses = new ChunkStatus[0];
    private AsyncTask task = AsyncTask.noop();
    private ChunkMargin margin = ChunkMargin.none();
    private int index = -1;

    ChunkStep(String name) {
        this.name = name;
    }

    ChunkStep margin(ChunkMargin margin) {
        this.margin = margin;
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

    public ChunkMargin getMargin() {
        return this.margin;
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

    @Nullable
    public static ChunkStep byIndex(int index) {
        if (index < 0 || index >= STEPS.length) {
            return null;
        }
        return STEPS[index];
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

    private static int computeRadiusFor(ChunkStep step) {
        int radius = 0;
        while (step != null) {
            radius += step.getMargin().radius;
            step = step.getPrevious();
        }

        return radius;
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

        // initialize step indices
        for (int index = 0; index < STEPS.length; index++) {
            STEPS[index].index = index;
        }

        // compute the effective radius for each step
        int maxDistance = 0;
        int[] stepToRadius = new int[STEPS.length];

        for (ChunkStep step : STEPS) {
            int radius = computeRadiusFor(step);
            stepToRadius[step.getIndex()] = radius;

            maxDistance = Math.max(radius, maxDistance);
        }

        STEP_TO_RADIUS = stepToRadius;
        MAX_DISTANCE = maxDistance;

        // initialize mappings between distance -> step and step -> distance

        ChunkStep[] distanceToStep = new ChunkStep[MAX_DISTANCE + 1];
        int[] stepToDistance = new int[STEPS.length];

        ChunkStep currentStep = STEPS[STEPS.length - 1];
        int currentDistance = 0;

        distanceToStep[0] = currentStep;

        while (currentStep != null) {
            int index = currentStep.getIndex();
            stepToDistance[index] = currentDistance;

            ChunkStep prevStep = currentStep.getPrevious();

            ChunkMargin margin = currentStep.getMargin();

            // minimum and maximum distance for the previous chunk step
            int start = currentDistance + 1;
            int end = currentDistance + margin.radius;

            for (int distance = start; distance <= end; distance++) {
                distanceToStep[distance] = prevStep;
            }

            currentDistance = end;

            currentStep = prevStep;
        }

        DISTANCE_TO_STEP = distanceToStep;
        STEP_TO_DISTANCE = stepToDistance;
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

        Heightmap.populateHeightmaps(ctx.chunk, WG_HEIGHTMAPS);

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
