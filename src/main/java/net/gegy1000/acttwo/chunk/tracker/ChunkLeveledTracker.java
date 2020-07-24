package net.gegy1000.acttwo.chunk.tracker;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.gegy1000.acttwo.ActTwo;
import net.gegy1000.acttwo.ActTwoDebugData;
import net.gegy1000.acttwo.chunk.ChunkController;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTaskPrioritySystem;
import net.minecraft.server.world.ChunkTicket;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.Pair;
import net.minecraft.util.collection.SortedArraySet;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.network.ServerSidePacketRegistry;
import net.fabricmc.fabric.api.server.PlayerStream;

public final class ChunkLeveledTracker extends ChunkTicketManager implements ChunkHolder.LevelUpdateListener {
    public static final int MAX_LEVEL = ThreadedAnvilChunkStorage.MAX_LEVEL;

    private final ServerWorld world;
    private final ChunkMap access;

    // Debug only!
    private final Queue<Pair<Long, Integer>> ticketCache = new ArrayDeque<>();

    ChunkLeveledTracker(ServerWorld world, ChunkMap access, Executor threadPool, Executor mainThread) {
        super(threadPool, mainThread);
        this.world = world;
        this.access = access;
    }

    public boolean tick(ChunkController controller) {
        this.distanceFromNearestPlayerTracker.updateLevels();
        this.nearbyChunkTicketUpdater.updateLevels();

        int completedUpdates = Integer.MAX_VALUE - this.distanceFromTicketTracker.update(Integer.MAX_VALUE);

        if (!this.chunkHolders.isEmpty()) {
            this.updateEntryLevels(controller);
            return true;
        }

        if (!this.chunkPositions.isEmpty()) {
            this.schedulePlayerTicketUnblocks(controller);
        }

        return completedUpdates != 0;
    }

    private void updateEntryLevels(ChunkController controller) {
        for (ChunkHolder entry : this.chunkHolders) {
            ((ChunkEntry) entry).onUpdateLevel(controller);
        }
        this.chunkHolders.clear();
    }

    private void schedulePlayerTicketUnblocks(ChunkController controller) {
        LongIterator iterator = this.chunkPositions.iterator();
        while (iterator.hasNext()) {
            long pos = iterator.nextLong();
            if (!this.hasPlayerTicketAt(pos)) {
                continue;
            }

            ChunkEntry entry = this.access.primary().getEntry(pos);
            if (entry == null) {
                throw new IllegalStateException("ticket with no entry");
            }

            controller.spawnOnMainThread(entry, entry.awaitEntitiesTickable().map(unit -> {
                this.unblockChunk(pos);
                return unit;
            }));
        }

        this.chunkPositions.clear();
    }

    private void unblockChunk(long pos) {
        this.playerTicketThrottlerSorter.send(ChunkTaskPrioritySystem.createSorterMessage(() -> {}, pos, false));
    }

    private boolean hasPlayerTicketAt(long pos) {
        SortedArraySet<ChunkTicket<?>> tickets = this.ticketsByPosition.get(pos);
        if (tickets == null) {
            return false;
        }

        for (ChunkTicket<?> ticket : tickets) {
            if (ticket.getType() == ChunkTicketType.PLAYER) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void purge() {
        this.age++;

        ObjectIterator<Long2ObjectMap.Entry<SortedArraySet<ChunkTicket<?>>>> iterator = this.ticketsByPosition.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<SortedArraySet<ChunkTicket<?>>> entry = iterator.next();

            long pos = entry.getLongKey();
            SortedArraySet<ChunkTicket<?>> tickets = entry.getValue();

            if (this.purgeExpiredTickets(tickets)) {
                this.distanceFromTicketTracker.updateLevel(pos, getLevelByTicket(tickets), false);
            }

            if (tickets.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private boolean purgeExpiredTickets(SortedArraySet<ChunkTicket<?>> tickets) {
        boolean removedAny = false;

        Iterator<ChunkTicket<?>> iterator = tickets.iterator();
        while (iterator.hasNext()) {
            ChunkTicket<?> ticket = iterator.next();
            if (ticket.isExpired(this.age)) {
                iterator.remove();
                removedAny = true;
            }
        }

        return removedAny;
    }

    private static int getLevelByTicket(SortedArraySet<ChunkTicket<?>> tickets) {
        if (!tickets.isEmpty()) {
            return tickets.first().getLevel();
        }
        return MAX_LEVEL + 1;
    }

    @Override
    @Deprecated
    public boolean tick(ThreadedAnvilChunkStorage tacs) {
        return this.tick(ChunkController.from(tacs));
    }

    @Override
    public void setWatchDistance(int viewDistance) {
        super.setWatchDistance(viewDistance);
    }

    @Override
    protected boolean isUnloaded(long pos) {
        return this.access.getQueues().isQueuedForUnload(pos);
    }

    @Nullable
    @Override
    protected ChunkHolder getChunkHolder(long pos) {
        return this.access.primary().getEntry(pos);
    }

    @Nullable
    @Override
    protected ChunkHolder setLevel(long pos, int toLevel, @Nullable ChunkHolder entry, int fromLevel) {
        if (isUnloaded(fromLevel) && isUnloaded(toLevel)) {
            return entry;
        }

        // Send debug data if enabled
        if (ActTwoDebugData.RENDER_CHUNK_TICKETS) {
            List<PlayerEntity> players = PlayerStream.world(this.world).collect(Collectors.toList());

            if (players.size() > 0) {
                players.forEach((player) -> sendChunkTicketData(player, pos, toLevel));

                while (ticketCache.size() > 0) {
                    Pair<Long, Integer> val = ticketCache.poll();

                    players.forEach((player) -> sendChunkTicketData(player, val.getLeft(), val.getRight()));
                }

            } else {
                ticketCache.add(new Pair<>(pos, toLevel));
            }
        }

        if (entry != null) {
            return this.updateLevel(pos, toLevel, entry);
        } else {
            return this.createAtLevel(pos, toLevel);
        }
    }

    private ChunkHolder updateLevel(long pos, int toLevel, ChunkHolder entry) {
        entry.setLevel(toLevel);

        if (isUnloaded(toLevel)) {
            this.access.getQueues().unloadEntry(pos);
        } else {
            this.access.getQueues().cancelUnloadEntry(pos);
        }

        return entry;
    }

    @Nullable
    private ChunkHolder createAtLevel(long pos, int toLevel) {
        if (isUnloaded(toLevel)) {
            return null;
        }

        return this.access.getQueues().loadEntry(new ChunkPos(pos), toLevel);
    }

    @Override
    public void updateLevel(ChunkPos pos, IntSupplier levelGetter, int targetLevel, IntConsumer levelSetter) {
        levelSetter.accept(targetLevel);
    }

    public static boolean isLoaded(int level) {
        return level <= MAX_LEVEL;
    }

    public static boolean isUnloaded(int level) {
        return level > MAX_LEVEL;
    }

    private void sendChunkTicketData(PlayerEntity player, long pos, int toLevel) {
        PacketByteBuf data = new PacketByteBuf(Unpooled.buffer());

        data.writeLong(pos);
        data.writeInt(toLevel);
        data.writeLong(System.currentTimeMillis() + 2000);

        ServerSidePacketRegistry.INSTANCE.sendToPlayer(player, ActTwo.DEBUG_CHUNK_TICKETS, data);
    }
}
