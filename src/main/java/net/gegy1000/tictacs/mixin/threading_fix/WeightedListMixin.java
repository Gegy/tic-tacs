package net.gegy1000.tictacs.mixin.threading_fix;

import net.minecraft.util.collection.WeightedList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Random;

@Mixin(WeightedList.class)
public class WeightedListMixin<U> {
    @Shadow
    @Final
    protected List<WeightedList.Entry<U>> entries;

    /**
     * @reason remove use of streams and support concurrent access
     * @author gegy1000
     */
    @Overwrite
    public U pickRandom(Random random) {
        // TODO: does this break vanilla parity?
        WeightedList.Entry<U> entry = this.entries.get(random.nextInt(this.entries.size()));
        return entry.getElement();
    }
}
