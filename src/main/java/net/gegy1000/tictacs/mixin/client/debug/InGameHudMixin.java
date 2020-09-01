package net.gegy1000.tictacs.mixin.client.debug;

import net.gegy1000.tictacs.client.LevelMapOverlay;
import net.gegy1000.tictacs.config.TicTacsConfig;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    private final LevelMapOverlay levelMap = new LevelMapOverlay();

    @Inject(method = "render", at = @At("RETURN"))
    private void render(MatrixStack transform, float deltaTime, CallbackInfo ci) {
        if (TicTacsConfig.get().debug.chunkMap) {
            this.levelMap.render(transform);
        }
    }
}
