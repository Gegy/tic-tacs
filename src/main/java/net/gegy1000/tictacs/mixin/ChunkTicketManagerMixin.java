package net.gegy1000.tictacs.mixin;

import net.minecraft.server.world.ChunkTicketManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ChunkTicketManager.class)
public class ChunkTicketManagerMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 4))
    private int getMaxTasks(int maxTasks) {
        return 128;
    }
}
