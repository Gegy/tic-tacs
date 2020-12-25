package net.gegy1000.tictacs.chunk.io;

import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.fabric.api.util.NbtType;
import net.gegy1000.tictacs.PoiStorageAccess;
import net.gegy1000.tictacs.compatibility.TicTacsCompatibility;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.SimpleTickScheduler;
import net.minecraft.structure.StructureManager;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.MutableRegistry;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.ChunkTickScheduler;
import net.minecraft.world.Heightmap;
import net.minecraft.world.TickScheduler;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeArray;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.feature.StructureFeature;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.LongStream;

public final class ChunkData {
    private static final Logger LOGGER = LogManager.getLogger(ChunkData.class);

    private static final int STARLIGHT_LIGHT_VERSION = 1;

    private final ChunkPos pos;
    private final ChunkStatus status;
    private final long inhabitedTime;
    private final UpgradeData upgradeData;

    @Nullable
    private final int[] biomeIds;

    private final ChunkSection[] sections;
    private final boolean[] sectionHasPois;

    private final ChunkLightData lightData;
    private final boolean lightOn;

    private final Map<Heightmap.Type, long[]> heightmaps;

    private final TickScheduler<Block> blockTickScheduler;
    private final TickScheduler<Fluid> fluidTickScheduler;
    private final List<BlockPos> blocksForPostProcessing;

    private final List<CompoundTag> entityTags;
    private final List<CompoundTag> blockEntityTags;

    private final Map<StructureFeature<?>, CompoundTag> structureStarts;
    private final Map<StructureFeature<?>, LongSet> structureReferences;

    private final boolean shouldSave;

    @Nullable
    private final ProtoData protoData;

    private ChunkData(
            ChunkPos pos, ChunkStatus status,
            long inhabitedTime, UpgradeData upgradeData,
            @Nullable int[] biomeIds, ChunkSection[] sections,
            boolean[] sectionHasPois,
            ChunkLightData lightData, boolean lightOn,
            Map<Heightmap.Type, long[]> heightmaps,
            TickScheduler<Block> blockTickScheduler, TickScheduler<Fluid> fluidTickScheduler,
            List<BlockPos> blocksForPostProcessing,
            List<CompoundTag> entityTags, List<CompoundTag> blockEntityTags,
            Map<StructureFeature<?>, CompoundTag> structureStarts,
            Map<StructureFeature<?>, LongSet> structureReferences,
            boolean shouldSave,
            @Nullable ProtoData protoData
    ) {
        this.pos = pos;
        this.status = status;
        this.inhabitedTime = inhabitedTime;
        this.upgradeData = upgradeData;
        this.biomeIds = biomeIds;
        this.sections = sections;
        this.sectionHasPois = sectionHasPois;
        this.lightData = lightData;
        this.lightOn = lightOn;
        this.heightmaps = heightmaps;
        this.blockTickScheduler = blockTickScheduler;
        this.fluidTickScheduler = fluidTickScheduler;
        this.blocksForPostProcessing = blocksForPostProcessing;
        this.entityTags = entityTags;
        this.blockEntityTags = blockEntityTags;
        this.structureStarts = structureStarts;
        this.structureReferences = structureReferences;
        this.shouldSave = shouldSave;
        this.protoData = protoData;
    }

    public static ChunkData deserialize(ChunkPos chunkPos, CompoundTag tag) {
        CompoundTag levelTag = tag.getCompound("Level");

        ChunkStatus status = ChunkStatus.byId(levelTag.getString("Status"));

        ChunkPos serializedPos = new ChunkPos(levelTag.getInt("xPos"), levelTag.getInt("zPos"));
        if (!serializedPos.equals(chunkPos)) {
            LOGGER.error("Chunk file at {} is in the wrong location; relocating. (Expected {}, got {})", chunkPos, chunkPos, serializedPos);
        }

        int[] biomeIds = levelTag.contains("Biomes", NbtType.INT_ARRAY) ? levelTag.getIntArray("Biomes") : null;
        UpgradeData upgradeData = levelTag.contains("UpgradeData", NbtType.COMPOUND) ? new UpgradeData(levelTag.getCompound("UpgradeData")) : UpgradeData.NO_UPGRADE_DATA;

        TickScheduler<Block> blockScheduler = new ChunkTickScheduler<>(block -> {
            return block == null || block.getDefaultState().isAir();
        }, chunkPos, levelTag.getList("ToBeTicked", NbtType.LIST));

        TickScheduler<Fluid> fluidScheduler = new ChunkTickScheduler<>(fluid -> {
            return fluid == null || fluid == Fluids.EMPTY;
        }, chunkPos, levelTag.getList("LiquidsToBeTicked", NbtType.LIST));

        ChunkSection[] sections = new ChunkSection[16];
        boolean[] sectionHasPois = new boolean[16];

        boolean lightOn = levelTag.getBoolean("isLightOn");

        ChunkLightData lightData;

        if (TicTacsCompatibility.STARLIGHT_LOADED) {
            lightData = new StarlightChunkLightData();
            lightOn = levelTag.getInt("starlight.light_versiom") == STARLIGHT_LIGHT_VERSION;
        } else {
            lightData = new VanillaChunkLightData();
        }

        ListTag sectionsList = levelTag.getList("Sections", NbtType.COMPOUND);

        for (int i = 0; i < sectionsList.size(); i++) {
            CompoundTag sectionTag = sectionsList.getCompound(i);
            int sectionY = sectionTag.getByte("Y");

            if (sectionTag.contains("Palette", NbtType.LIST) && sectionTag.contains("BlockStates", NbtType.LONG_ARRAY)) {
                ChunkSection section = new ChunkSection(sectionY << 4);

                ListTag palette = sectionTag.getList("Palette", NbtType.COMPOUND);
                long[] data = sectionTag.getLongArray("BlockStates");
                section.getContainer().read(palette, data);

                section.calculateCounts();

                if (!section.isEmpty()) {
                    sections[sectionY] = section;
                    sectionHasPois[sectionY] = section.hasAny(PointOfInterestType.REGISTERED_STATES::contains);
                }
            }

            if (lightOn) {
                lightData.acceptSection(sectionY, sectionTag, status);
            }
        }

        ChunkStatus.ChunkType chunkType = status.getChunkType();

        List<CompoundTag> entityTags = new ArrayList<>();
        List<CompoundTag> blockEntityTags = new ArrayList<>();

        ListTag entitiesList = levelTag.getList("Entities", NbtType.COMPOUND);
        for (int i = 0; i < entitiesList.size(); i++) {
            entityTags.add(entitiesList.getCompound(i));
        }

        ListTag blockEntitiesList = levelTag.getList("TileEntities", NbtType.COMPOUND);
        for (int i = 0; i < blockEntitiesList.size(); i++) {
            blockEntityTags.add(blockEntitiesList.getCompound(i));
        }

        if (chunkType == ChunkStatus.ChunkType.field_12807) {
            if (levelTag.contains("TileTicks", NbtType.LIST)) {
                blockScheduler = SimpleTickScheduler.fromNbt(levelTag.getList("TileTicks", NbtType.COMPOUND), Registry.BLOCK::getId, Registry.BLOCK::get);
            }

            if (levelTag.contains("LiquidTicks", NbtType.LIST)) {
                fluidScheduler = SimpleTickScheduler.fromNbt(levelTag.getList("LiquidTicks", NbtType.COMPOUND), Registry.FLUID::getId, Registry.FLUID::get);
            }
        }

        CompoundTag heightmapsTag = levelTag.getCompound("Heightmaps");

        Map<Heightmap.Type, long[]> heightmaps = new EnumMap<>(Heightmap.Type.class);
        for (Heightmap.Type type : status.getHeightmapTypes()) {
            String name = type.getName();
            if (heightmapsTag.contains(name, NbtType.LONG_ARRAY)) {
                heightmaps.put(type, heightmapsTag.getLongArray(name));
            }
        }

        CompoundTag structuresTag = levelTag.getCompound("Structures");
        Map<StructureFeature<?>, CompoundTag> structureStarts = deserializeStructureStarts(structuresTag);
        Map<StructureFeature<?>, LongSet> structureReferences = deserializeStructureReferences(chunkPos, structuresTag);

        List<BlockPos> blocksForPostProcessing = new ArrayList<>();

        ListTag postProcessingList = levelTag.getList("PostProcessing", NbtType.LIST);
        for (int sectionY = 0; sectionY < postProcessingList.size(); sectionY++) {
            ListTag queueList = postProcessingList.getList(sectionY);
            for (int i = 0; i < queueList.size(); i++) {
                BlockPos pos = ProtoChunk.joinBlockPos(queueList.getShort(sectionY), sectionY, chunkPos);
                blocksForPostProcessing.add(pos);
            }
        }

        ProtoData protoData = null;
        if (chunkType == ChunkStatus.ChunkType.field_12808) {
            protoData = deserializeProtoData(chunkPos, levelTag, status, sections, lightOn);
        }

        long inhabitedTime = levelTag.getLong("InhabitedTime");
        boolean shouldSave = levelTag.getBoolean("shouldSave");

        return new ChunkData(
                chunkPos, status,
                inhabitedTime, upgradeData,
                biomeIds, sections, sectionHasPois,
                lightData, lightOn,
                heightmaps, blockScheduler, fluidScheduler,
                blocksForPostProcessing, entityTags, blockEntityTags,
                structureStarts, structureReferences,
                shouldSave,
                protoData
        );
    }

    private static Map<StructureFeature<?>, CompoundTag> deserializeStructureStarts(CompoundTag tag) {
        Map<StructureFeature<?>, CompoundTag> starts = new Object2ObjectOpenHashMap<>();

        CompoundTag startsTag = tag.getCompound("Starts");

        for (String key : startsTag.getKeys()) {
            StructureFeature<?> feature = StructureFeature.STRUCTURES.get(key.toLowerCase(Locale.ROOT));
            if (feature == null) {
                LOGGER.error("Unknown structure start: {}", key);
                continue;
            }

            starts.put(feature, startsTag.getCompound(key));
        }

        return starts;
    }

    private static Map<StructureFeature<?>, LongSet> deserializeStructureReferences(ChunkPos pos, CompoundTag tag) {
        Map<StructureFeature<?>, LongSet> references = new Object2ObjectOpenHashMap<>();
        CompoundTag referencesTag = tag.getCompound("References");

        for (String key : referencesTag.getKeys()) {
            StructureFeature<?> feature = StructureFeature.STRUCTURES.get(key.toLowerCase(Locale.ROOT));

            LongStream referenceStream = Arrays.stream(referencesTag.getLongArray(key)).filter(reference -> {
                ChunkPos chunkPos = new ChunkPos(reference);
                if (chunkPos.method_24022(pos) > 8) {
                    LOGGER.warn("Found invalid structure reference [{} @ {}] for chunk {}", key, chunkPos, pos);
                    return false;
                }
                return true;
            });

            references.put(feature, new LongOpenHashSet(referenceStream.toArray()));
        }

        return references;
    }

    private static ProtoData deserializeProtoData(ChunkPos chunkPos, CompoundTag levelTag, ChunkStatus status, ChunkSection[] sections, boolean lightOn) {
        List<BlockPos> lightSources = new ArrayList<>();

        ListTag lightSectionList = levelTag.getList("Lights", NbtType.LIST);
        for (int sectionY = 0; sectionY < lightSectionList.size(); sectionY++) {
            ListTag lightList = lightSectionList.getList(sectionY);
            for (int i = 0; i < lightList.size(); i++) {
                lightSources.add(ProtoChunk.joinBlockPos(lightList.getShort(i), sectionY, chunkPos));
            }
        }

        Map<GenerationStep.Carver, BitSet> carvingMasks = new EnumMap<>(GenerationStep.Carver.class);

        CompoundTag carvingMasksTag = levelTag.getCompound("CarvingMasks");
        for (String key : carvingMasksTag.getKeys()) {
            GenerationStep.Carver carver = GenerationStep.Carver.valueOf(key);
            carvingMasks.put(carver, BitSet.valueOf(carvingMasksTag.getByteArray(key)));
        }

        if (!lightOn && status.isAtLeast(ChunkStatus.LIGHT)) {
            for (BlockPos pos : BlockPos.iterate(0, 0, 0, 15, 255, 15)) {
                ChunkSection section = sections[pos.getY() >> 4];
                if (section == null) {
                    continue;
                }

                BlockState state = section.getBlockState(pos.getX(), pos.getY() & 15, pos.getZ());
                if (state.getLuminance() != 0) {
                    lightSources.add(new BlockPos(
                            chunkPos.getStartX() + pos.getX(),
                            pos.getY(),
                            chunkPos.getStartZ() + pos.getZ()
                    ));
                }
            }
        }

        return new ProtoData(lightSources, carvingMasks);
    }

    public Chunk createChunk(ServerWorld world, StructureManager structures, PointOfInterestStorage poi) {
        DynamicRegistryManager registryManager = world.getRegistryManager();
        ServerChunkManager chunkManager = world.getChunkManager();
        ChunkGenerator chunkGenerator = chunkManager.getChunkGenerator();
        LightingProvider lightingProvider = chunkManager.getLightingProvider();
        BiomeSource biomeSource = chunkGenerator.getBiomeSource();

        BiomeArray biomes = null;
        if (this.biomeIds != null || this.status.isAtLeast(ChunkStatus.BIOMES)) {
            MutableRegistry<Biome> biomeRegistry = registryManager.get(Registry.BIOME_KEY);
            biomes = new BiomeArray(biomeRegistry, this.pos, biomeSource, this.biomeIds);
        }

        this.lightData.applyToWorld(this.pos, world);

        ChunkStatus.ChunkType chunkType = this.status.getChunkType();

        Chunk chunk;
        ProtoChunk protoChunk;

        if (chunkType == ChunkStatus.ChunkType.field_12807) {
            WorldChunk worldChunk = this.createWorldChunk(world, biomes);
            chunk = worldChunk;
            protoChunk = new ReadOnlyChunk(worldChunk);
        } else {
            protoChunk = this.createProtoChunk(lightingProvider, biomes);
            chunk = protoChunk;
        }

        for (int sectionY = 0; sectionY < this.sectionHasPois.length; sectionY++) {
            if (this.sectionHasPois[sectionY]) {
                ((PoiStorageAccess) poi).initSectionWithPois(this.pos, this.sections[sectionY]);
            }
        }

        this.populateStructures(chunk, structures, world.getSeed());
        this.populateHeightmaps(chunk);

        if (this.shouldSave) {
            chunk.setShouldSave(true);
        }

        for (BlockPos pos : this.blocksForPostProcessing) {
            chunk.markBlockForPostProcessing(ProtoChunk.getPackedSectionRelative(pos), pos.getY() >> 4);
        }

        protoChunk.setLightOn(this.lightOn);

        this.lightData.applyToChunk(protoChunk);

        return protoChunk;
    }

    private void populateHeightmaps(Chunk chunk) {
        if (!this.status.isAtLeast(ChunkStatus.NOISE)) {
            return;
        }

        EnumSet<Heightmap.Type> missingHeightmaps = EnumSet.noneOf(Heightmap.Type.class);
        for (Heightmap.Type type : this.status.getHeightmapTypes()) {
            long[] heightmap = this.heightmaps.get(type);
            if (heightmap != null) {
                chunk.setHeightmap(type, heightmap);
            } else {
                missingHeightmaps.add(type);
            }
        }

        if (!missingHeightmaps.isEmpty()) {
            Heightmap.populateHeightmaps(chunk, missingHeightmaps);
        }
    }

    private void populateStructures(Chunk chunk, StructureManager structures, long worldSeed) {
        Map<StructureFeature<?>, StructureStart<?>> structureStarts = new Object2ObjectOpenHashMap<>();
        for (Map.Entry<StructureFeature<?>, CompoundTag> entry : this.structureStarts.entrySet()) {
            StructureStart<?> start = StructureFeature.readStructureStart(structures, entry.getValue(), worldSeed);
            if (start != null) {
                structureStarts.put(entry.getKey(), start);
            }
        }

        chunk.setStructureStarts(structureStarts);
        chunk.setStructureReferences(this.structureReferences);
    }

    private WorldChunk createWorldChunk(ServerWorld world, BiomeArray biomes) {
        List<CompoundTag> entityTags = this.entityTags;
        List<CompoundTag> blockEntityTags = this.blockEntityTags;
        Consumer<WorldChunk> loadToWorld = worldChunk -> addEntitiesToWorldChunk(worldChunk, entityTags, blockEntityTags);

        return new WorldChunk(
                world, this.pos, biomes, this.upgradeData,
                this.blockTickScheduler, this.fluidTickScheduler,
                this.inhabitedTime,
                this.sections,
                loadToWorld
        );
    }

    private ProtoChunk createProtoChunk(LightingProvider lightingProvider, BiomeArray biomes) {
        ProtoChunk chunk = new ProtoChunk(
                this.pos, this.upgradeData,
                this.sections,
                (ChunkTickScheduler<Block>) this.blockTickScheduler,
                (ChunkTickScheduler<Fluid>) this.fluidTickScheduler
        );

        chunk.setBiomes(biomes);
        chunk.setInhabitedTime(this.inhabitedTime);
        chunk.setStatus(this.status);

        if (this.status.isAtLeast(ChunkStatus.FEATURES)) {
            chunk.setLightingProvider(lightingProvider);
        }

        for (CompoundTag tag : this.entityTags) {
            chunk.addEntity(tag);
        }

        for (CompoundTag tag : this.blockEntityTags) {
            chunk.addPendingBlockEntityTag(tag);
        }

        Preconditions.checkNotNull(this.protoData, "loaded no proto data for ProtoChunk");

        for (BlockPos pos : this.protoData.lightSources) {
            chunk.addLightSource(pos);
        }

        for (Map.Entry<GenerationStep.Carver, BitSet> entry : this.protoData.carvingMasks.entrySet()) {
            chunk.setCarvingMask(entry.getKey(), entry.getValue());
        }

        return chunk;
    }

    private static void addEntitiesToWorldChunk(WorldChunk chunk, List<CompoundTag> entityTags, List<CompoundTag> blockEntityTags) {
        World world = chunk.getWorld();
        for (CompoundTag tag : entityTags) {
            EntityType.loadEntityWithPassengers(tag, world, entity -> {
                chunk.addEntity(entity);
                return entity;
            });
            chunk.setUnsaved(true);
        }

        for (CompoundTag tag : blockEntityTags) {
            if (!tag.getBoolean("keepPacked")) {
                BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                BlockEntity entity = BlockEntity.createFromTag(chunk.getBlockState(pos), tag);
                if (entity != null) {
                    chunk.addBlockEntity(entity);
                }
            } else {
                chunk.addPendingBlockEntityTag(tag);
            }
        }
    }

    private static class ProtoData {
        final List<BlockPos> lightSources;
        final Map<GenerationStep.Carver, BitSet> carvingMasks;

        ProtoData(List<BlockPos> lightSources, Map<GenerationStep.Carver, BitSet> carvingMasks) {
            this.lightSources = lightSources;
            this.carvingMasks = carvingMasks;
        }
    }
}
