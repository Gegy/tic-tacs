package net.gegy1000.tictacs.mixin.threading_fix;

import com.mojang.datafixers.DataFixer;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.Structure;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(StructureManager.class)
public class StructureManagerMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Identifier, Structure> structures;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(ResourceManager resourceManager, LevelStorage.Session session, DataFixer dataFixer, CallbackInfo ci) {
        // wrap the structures map so that it can be accessed concurrently
        this.structures = new ConcurrentHashMap<>(this.structures);
    }
}
