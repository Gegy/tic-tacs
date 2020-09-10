package net.gegy1000.tictacs.mixin.client;

import net.gegy1000.tictacs.config.TicTacsConfig;
import net.minecraft.client.options.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(floatValue = 32.0F))
    private float modifyMaxViewDistance(float viewDistance) {
        return TicTacsConfig.get().maxViewDistance;
    }
}
