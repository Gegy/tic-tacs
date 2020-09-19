package net.gegy1000.tictacs.mixin;

import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.minecraft.server.world.ChunkTicketManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ChunkTicketManager.class)
public class ChunkTicketManagerMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 33))
    private int getFullLevelForNearbyChunkTicketUpdater(int level) {
        return ChunkLevelTracker.FULL_LEVEL;
    }

    @ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 33))
    private static int getFullLevelForNearbyPlayerTicketLevel(int level) {
        return ChunkLevelTracker.FULL_LEVEL;
    }

    @ModifyConstant(method = "addTicket(Lnet/minecraft/server/world/ChunkTicketType;Lnet/minecraft/util/math/ChunkPos;ILjava/lang/Object;)V", constant = @Constant(intValue = 33))
    private int getFullChunkLevelForAddTicket(int level) {
        return ChunkLevelTracker.FULL_LEVEL;
    }

    @ModifyConstant(method = "removeTicket(Lnet/minecraft/server/world/ChunkTicketType;Lnet/minecraft/util/math/ChunkPos;ILjava/lang/Object;)V", constant = @Constant(intValue = 33))
    private int getFullChunkLevelForRemoveTicket(int level) {
        return ChunkLevelTracker.FULL_LEVEL;
    }
}
