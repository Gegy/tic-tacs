package net.gegy1000.tictacs.mixin.ticket;

import net.gegy1000.tictacs.chunk.ticket.TicketTracker;
import net.minecraft.server.world.ChunkTaskPrioritySystem;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

@Mixin(ChunkTicketManager.NearbyChunkTicketUpdater.class)
public class NearbyChunkTicketUpdaterMixin {
    @Shadow(aliases = "field_17463")
    private ChunkTicketManager ticketManager;

    /**
     * @author gegy1000
     * @see ChunkTicketManagerMixin
     */
    @Overwrite
    public void updateTicket(long pos, int distance, boolean wasTracked, boolean isTracked) {
        if (wasTracked == isTracked) {
            return;
        }

        TicketTracker ticketTracker = (TicketTracker) this.ticketManager;
        if (isTracked) {
            ticketTracker.enqueueTicket(pos, distance);
        } else {
            ticketTracker.removeTicket(pos);
        }
    }

    @Redirect(
            method = "updateLevels",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ChunkTaskPrioritySystem;updateLevel(Lnet/minecraft/util/math/ChunkPos;Ljava/util/function/IntSupplier;ILjava/util/function/IntConsumer;)V"
            )
    )
    private void updateLevel(ChunkTaskPrioritySystem ctps, ChunkPos pos, IntSupplier getLevel, int targetLevel, IntConsumer setLevel) {
        TicketTracker ticketTracker = (TicketTracker) this.ticketManager;
        ticketTracker.moveTicket(pos.toLong(), getLevel.getAsInt(), targetLevel);
        setLevel.accept(targetLevel);
    }
}
