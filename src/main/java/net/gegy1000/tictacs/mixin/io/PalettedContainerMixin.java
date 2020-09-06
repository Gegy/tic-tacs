package net.gegy1000.tictacs.mixin.io;

import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> {
    private static final int SIZE = 16 * 16 * 16;

    @Shadow
    private int paletteSize;

    @Shadow
    private Palette<T> palette;

    @Inject(method = "count", at = @At("HEAD"), cancellable = true)
    private void count(PalettedContainer.CountConsumer<T> consumer, CallbackInfo ci) {
        // test for uniformity on small palettes
        int paletteSize = this.paletteSize;
        if (paletteSize > 4) {
            return;
        }

        Palette<T> palette = this.palette;
        T uniformValue = null;

        for (int i = 0; i < paletteSize; i++) {
            T entry = palette.getByIndex(i);
            if (entry == null || entry == uniformValue) {
                continue;
            }

            if (uniformValue == null) {
                uniformValue = entry;
            } else {
                // we have more than one value, delegate to default behavior
                return;
            }
        }

        // this section only has 1 palette entry!
        consumer.accept(uniformValue, SIZE);
        ci.cancel();
    }
}
