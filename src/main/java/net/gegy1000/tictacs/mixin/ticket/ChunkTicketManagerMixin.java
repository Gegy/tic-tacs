package net.gegy1000.tictacs.mixin.ticket;

import it.unimi.dsi.fastutil.longs.LongList;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.tictacs.chunk.ticket.PlayerTicketManager;
import net.gegy1000.tictacs.chunk.ticket.TicketTracker;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Comparator;
import java.util.Set;

@Mixin(ChunkTicketManager.class)
public abstract class ChunkTicketManagerMixin implements TicketTracker {
    private static final ChunkTicketType<ChunkPos> GENERATION_TICKET = ChunkTicketType.create("player_generation", Comparator.comparingLong(ChunkPos::toLong));

    @Shadow
    @Final
    public ChunkTicketManager.DistanceFromNearestPlayerTracker distanceFromNearestPlayerTracker;
    @Shadow
    @Final
    public ChunkTicketManager.NearbyChunkTicketUpdater nearbyChunkTicketUpdater;
    @Shadow
    @Final
    public ChunkTicketManager.TicketDistanceLevelPropagator distanceFromTicketTracker;
    @Shadow
    @Final
    public Set<ChunkHolder> chunkHolders;

    private PlayerTicketManager fullTickets;
    private PlayerTicketManager generationTickets;

    /**
     * @reason redirect player ticket logic to {@link PlayerTicketManager}
     * @author gegy1000
     */
    @Overwrite
    public boolean tick(ThreadedAnvilChunkStorage tacs) {
        this.initialize(tacs);

        LongList fullTickets = this.fullTickets.collectTickets();
        LongList generationTickets = this.generationTickets.collectTickets();

        this.distanceFromNearestPlayerTracker.updateLevels();
        this.nearbyChunkTicketUpdater.updateLevels();

        int completedTasks = Integer.MAX_VALUE - this.distanceFromTicketTracker.update(Integer.MAX_VALUE);

        this.fullTickets.waitForChunks(fullTickets);
        this.generationTickets.waitForChunks(generationTickets);

        if (!this.chunkHolders.isEmpty()) {
            for (ChunkHolder holder : this.chunkHolders) {
                ChunkEntry entry = (ChunkEntry) holder;
                entry.onUpdateLevel(tacs);
            }
            this.chunkHolders.clear();
            return true;
        }

        return completedTasks != 0;
    }

    @Unique
    private void initialize(ThreadedAnvilChunkStorage tacs) {
        if (this.fullTickets == null || this.generationTickets == null) {
            ChunkController controller = (ChunkController) tacs;
            this.fullTickets = new PlayerTicketManager(controller, ChunkStep.FULL, 2, ChunkTicketType.PLAYER, 2);
            this.generationTickets = new PlayerTicketManager(controller, ChunkStep.GENERATION, 0, GENERATION_TICKET, 5);
        }
    }

    @Override
    public void enqueueTicket(long pos, int distance) {
        this.fullTickets.enqueueTicket(pos, distance);
        this.generationTickets.enqueueTicket(pos, distance);
    }

    @Override
    public void removeTicket(long pos) {
        this.fullTickets.removeTicket(pos);
        this.generationTickets.removeTicket(pos);
    }

    @Override
    public void moveTicket(long pos, int fromDistance, int toDistance) {
        this.fullTickets.moveTicket(pos, fromDistance, toDistance);
        this.generationTickets.moveTicket(pos, fromDistance, toDistance);
    }
}
