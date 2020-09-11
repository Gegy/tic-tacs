package net.gegy1000.tictacs.chunk.tracker;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;

import java.util.Collection;
import java.util.Iterator;

public final class ChunkPlayerWatchers implements Iterable<ServerPlayerEntity> {
    private final ServerWorld world;

    private final ReferenceSet<ServerPlayerEntity> players = new ReferenceOpenHashSet<>();
    private final ReferenceSet<ServerPlayerEntity> loadingPlayers = new ReferenceOpenHashSet<>();

    public ChunkPlayerWatchers(ServerWorld world) {
        this.world = world;
    }

    public void addPlayer(ServerPlayerEntity player) {
        this.players.add(player);
        if (this.shouldLoadChunks(player)) {
            this.loadingPlayers.add(player);
        }
    }

    public void removePlayer(ServerPlayerEntity player) {
        if (this.players.remove(player)) {
            this.loadingPlayers.remove(player);
        }
    }

    public void setLoadingEnabled(ServerPlayerEntity player, boolean enabled) {
        if (!this.players.contains(player)) {
            return;
        }

        if (enabled) {
            this.loadingPlayers.add(player);
        } else {
            this.loadingPlayers.remove(player);
        }
    }

    public boolean containsPlayer(ServerPlayerEntity player) {
        return this.players.contains(player);
    }

    public boolean isLoadingEnabled(ServerPlayerEntity player) {
        return this.loadingPlayers.contains(player);
    }

    public boolean shouldLoadChunks(ServerPlayerEntity player) {
        return !player.isSpectator() || this.world.getGameRules().getBoolean(GameRules.SPECTATORS_GENERATE_CHUNKS);
    }

    public boolean isEmpty() {
        return this.players.isEmpty();
    }

    public Collection<ServerPlayerEntity> getPlayers() {
        return this.players;
    }

    public Collection<ServerPlayerEntity> getLoadingPlayers() {
        return this.loadingPlayers;
    }

    @Override
    public Iterator<ServerPlayerEntity> iterator() {
        return this.players.iterator();
    }
}
