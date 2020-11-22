package net.gegy1000.tictacs.mixin.io;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Codec;
import net.gegy1000.tictacs.PoiStorageAccess;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.poi.PointOfInterestSet;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.storage.SerializingRegionBasedStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Mixin(PointOfInterestStorage.class)
public abstract class PointOfInterestStorageMixin extends SerializingRegionBasedStorage<PointOfInterestSet> implements PoiStorageAccess {
    public PointOfInterestStorageMixin(File directory, Function<Runnable, Codec<PointOfInterestSet>> codecFactory, Function<Runnable, PointOfInterestSet> factory, DataFixer dataFixer, DataFixTypes dataFixTypes, boolean bl, HeightLimitView heightLimitView) {
        super(directory, codecFactory, factory, dataFixer, dataFixTypes, bl, heightLimitView);
    }

    @Shadow
    protected abstract void scanAndPopulate(ChunkSection section, ChunkSectionPos sectionPos, BiConsumer<BlockPos, PointOfInterestType> add);

    @Override
    public void initSectionWithPois(ChunkPos pos, ChunkSection section) {
        ChunkSectionPos sectionPos = ChunkSectionPos.from(pos, section.getYOffset() >> 4);
        Util.ifPresentOrElse(this.get(sectionPos.asLong()), set -> {
            set.updatePointsOfInterest(add -> this.scanAndPopulate(section, sectionPos, add));
        }, () -> {
            PointOfInterestSet set = this.getOrCreate(sectionPos.asLong());
            this.scanAndPopulate(section, sectionPos, set::add);
        });
    }
}
