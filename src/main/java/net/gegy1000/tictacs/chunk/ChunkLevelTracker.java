package net.gegy1000.tictacs.chunk;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry;
import net.fabricmc.fabric.api.server.PlayerStream;
import net.gegy1000.tictacs.TicTacs;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.config.TicTacsConfig;
import net.gegy1000.tictacs.mixin.TacsAccessor;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.Pair;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

public final class ChunkLevelTracker {
    public static final int MAX_LEVEL = ThreadedAnvilChunkStorage.MAX_LEVEL;

    private final ServerWorld world;
    private final ChunkController controller;

    // Debug only!
    private final Queue<Pair<Long, Integer>> ticketCache = new ArrayDeque<>();

    public ChunkLevelTracker(ServerWorld world, ChunkController controller) {
        this.world = world;
        this.controller = controller;
    }

    @Nullable
    public ChunkHolder setLevel(long pos, int toLevel, @Nullable ChunkEntry entry, int fromLevel) {
        if (isUnloaded(fromLevel) && isUnloaded(toLevel)) {
            return entry;
        }

        // Send debug data if enabled
        if (TicTacsConfig.get().debug.chunkLevels) {
            List<PlayerEntity> players = PlayerStream.world(this.world).collect(Collectors.toList());

            if (players.size() > 0) {
                players.forEach(player -> this.sendChunkTicketData(player, pos, toLevel));

                while (this.ticketCache.size() > 0) {
                    Pair<Long, Integer> val = this.ticketCache.poll();
                    players.forEach(player -> this.sendChunkTicketData(player, val.getLeft(), val.getRight()));
                }
            } else {
                this.ticketCache.add(new Pair<>(pos, toLevel));
            }
        }

        if (entry != null) {
            return this.updateLevel(pos, toLevel, entry);
        } else {
            return this.createAtLevel(pos, toLevel);
        }
    }

    private ChunkEntry updateLevel(long pos, int toLevel, ChunkEntry entry) {
        entry.setLevel(toLevel);

        TacsAccessor accessor = (TacsAccessor) this.controller;
        LongSet unloadedChunks = accessor.getQueuedUnloads();

        if (isUnloaded(toLevel)) {
            unloadedChunks.add(pos);
        } else {
            unloadedChunks.remove(pos);
        }

        return entry;
    }

    @Nullable
    private ChunkEntry createAtLevel(long pos, int toLevel) {
        if (isUnloaded(toLevel)) {
            return null;
        }

        return this.controller.getMap().getOrCreateEntry(new ChunkPos(pos), toLevel);
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

        ServerSidePacketRegistry.INSTANCE.sendToPlayer(player, TicTacs.DEBUG_CHUNK_TICKETS, data);
    }
}
