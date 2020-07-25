package net.gegy1000.acttwo.chunk.step;

import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureManager;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;

import java.util.List;

public final class ChunkStepContext {
    public final ChunkController controller;
    public final ChunkEntryState entry;
    public final ServerWorld world;
    public final ChunkGenerator generator;
    public final StructureManager structures;
    public final ServerLightingProvider lighting;
    public final Chunk chunk;
    public final List<Chunk> chunks;

    private ChunkRegion region;
    private StructureAccessor structureAccessor;

    public ChunkStepContext(ChunkController controller, ChunkEntryState entry, ServerWorld world, ChunkGenerator generator, StructureManager structures, ServerLightingProvider lighting, Chunk chunk, List<Chunk> chunks) {
        this.controller = controller;
        this.entry = entry;
        this.world = world;
        this.generator = generator;
        this.structures = structures;
        this.lighting = lighting;
        this.chunk = chunk;
        this.chunks = chunks;
    }

    public ChunkRegion asRegion() {
        if (this.region == null) {
            this.region = new ChunkRegion(this.world, this.chunks);
        }
        return this.region;
    }

    public StructureAccessor asStructureAccessor() {
        if (this.structureAccessor == null) {
            ChunkRegion region = this.asRegion();
            this.structureAccessor = this.world.getStructureAccessor().method_29951(region);
        }
        return this.structureAccessor;
    }
}
