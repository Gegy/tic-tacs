package net.gegy1000.tictacs.mixin.serialization;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeArray;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.poi.PointOfInterestStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nullable;

@Mixin(ChunkSerializer.class)
public class MixinChunkSerializer {
    private static final BiomeArray DUMMY_BIOMES = new BiomeArray(null, (Biome[]) null);

    @Redirect(
            method = "deserialize",
            at = @At(
                    value = "NEW",
                    target = "net/minecraft/world/biome/source/BiomeArray"
            )
    )
    private static BiomeArray createBiomeArray(
            IndexedIterable<Biome> indexedIterable, ChunkPos initPos, BiomeSource biomeSource, @Nullable int[] is,
            ServerWorld world, StructureManager structureManager, PointOfInterestStorage poiStorage,
            ChunkPos pos, CompoundTag tag
    ) {
        if (is == null) {
            ChunkStatus status = ChunkStatus.byId(tag.getString("Status"));
            if (!status.isAtLeast(ChunkStatus.BIOMES)) {
                return DUMMY_BIOMES;
            }
        }

        return new BiomeArray(indexedIterable, initPos, biomeSource, is);
    }

    @ModifyArg(
            method = "deserialize",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/ProtoChunk;setBiomes(Lnet/minecraft/world/biome/source/BiomeArray;)V"
            )
    )
    private static BiomeArray swapDummyBiomeArray(BiomeArray biomes) {
        if (biomes == DUMMY_BIOMES) {
            return null;
        }
        return biomes;
    }
}
