package net.gegy1000.tictacs.chunk.ticket;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.entry.ChunkListener;
import net.gegy1000.tictacs.chunk.future.AwaitAll;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.tictacs.chunk.upgrade.ChunkUpgrader;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.ChunkPos;

// TODO: ideally we overwrite generation tickets with player tickets
public final class PlayerTicketManager implements TicketTracker {
    private final ChunkController controller;
    private final ChunkStep step;
    private final ChunkTicketType<ChunkPos> ticketType;
    private final int ticketLevel;
    private final int levelStep;

    private final int levelCount;
    private final LongSet[] queues;
    private final int[] waitingCounts;

    private int currentLevel;

    private final LongList addedChunks = new LongArrayList();

    public PlayerTicketManager(ChunkController controller, ChunkStep step, int radius, ChunkTicketType<ChunkPos> ticketType, int levelStep) {
        this.controller = controller;
        this.step = step;
        this.ticketType = ticketType;
        this.levelStep = levelStep;

        this.ticketLevel = ChunkLevelTracker.FULL_LEVEL + ChunkStep.getDistanceFromFull(step) - radius;

        this.levelCount = (ChunkLevelTracker.FULL_LEVEL + levelStep - 1) / levelStep;
        this.queues = new LongSet[this.levelCount];
        this.waitingCounts = new int[this.levelCount];

        for (int i = 0; i < this.levelCount; i++) {
            this.queues[i] = new LongOpenHashSet();
        }
    }

    public LongList collectTickets() {
        ChunkTicketManager ticketManager = this.controller.getTicketManager();

        while (true) {
            int currentLevel = this.currentLevel;
            if (currentLevel >= this.levelCount) {
                return LongLists.EMPTY_LIST;
            }

            LongSet queue = this.queues[currentLevel];

            // once this level is totally completed, we can advance to the next level
            if (queue.isEmpty() && this.waitingCounts[currentLevel] <= 0) {
                this.currentLevel = this.getNextLevel(currentLevel);
                continue;
            }

            if (!queue.isEmpty()) {
                LongList addedChunks = this.addedChunks;
                addedChunks.clear();

                addedChunks.addAll(queue);
                queue.clear();

                LongIterator iterator = addedChunks.iterator();
                while (iterator.hasNext()) {
                    ChunkPos pos = new ChunkPos(iterator.nextLong());
                    ticketManager.addTicketWithLevel(this.ticketType, pos, this.ticketLevel, pos);
                }

                return addedChunks;
            }

            return LongLists.EMPTY_LIST;
        }
    }

    public void waitForChunks(LongList chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        int count = chunks.size();

        int currentLevel = this.currentLevel;
        this.waitingCounts[currentLevel] += count;

        Future<Unit> future = this.awaitAllChunks(chunks);
        this.controller.spawnOnMainThread(future.map(unit -> {
            int waitingCount = this.waitingCounts[currentLevel];
            this.waitingCounts[currentLevel] = Math.max(waitingCount - count, 0);
            return unit;
        }));

        chunks.clear();
    }

    private Future<Unit> awaitAllChunks(LongList entries) {
        ChunkUpgrader upgrader = this.controller.getUpgrader();
        ChunkAccess chunks = this.controller.getMap().visible();

        ChunkListener[] listeners = new ChunkListener[entries.size()];

        for (int i = 0; i < entries.size(); i++) {
            long pos = entries.getLong(i);
            ChunkEntry entry = chunks.getEntry(pos);
            if (entry == null) {
                throw new IllegalStateException("missing entry added by player ticket");
            }

            upgrader.spawnUpgradeTo(entry, this.step);
            listeners[i] = entry.getListenerFor(this.step);
        }

        return AwaitAll.of(listeners);
    }

    @Override
    public void enqueueTicket(long pos, int distance) {
        int level = distance / this.levelStep;
        if (level >= this.levelCount) {
            return;
        }

        this.queues[level].add(pos);
        if (level < this.currentLevel) {
            this.currentLevel = level;
        }
    }

    @Override
    public void removeTicket(long pos) {
        this.removeChunkTicket(pos);

        for (int i = this.currentLevel; i < this.levelCount; i++) {
            LongSet queue = this.queues[i];
            if (queue.remove(pos)) {
                if (queue.isEmpty()) {
                    this.removeLevel(i);
                }
                return;
            }
        }
    }

    private void removeChunkTicket(long posKey) {
        ChunkPos pos = new ChunkPos(posKey);
        this.controller.getTicketManager().removeTicketWithLevel(this.ticketType, pos, this.ticketLevel, pos);
    }

    @Override
    public void moveTicket(long pos, int fromDistance, int toDistance) {
        int fromLevel = fromDistance / this.levelStep;
        if (fromLevel >= this.levelCount) {
            return;
        }

        LongSet fromQueue = this.queues[fromLevel];
        if (!fromQueue.remove(pos)) {
            return;
        }

        this.enqueueTicket(pos, toDistance);

        if (fromQueue.isEmpty()) {
            this.removeLevel(fromLevel);
        }
    }

    private void removeLevel(int level) {
        this.waitingCounts[level] = 0;
        if (level == this.currentLevel) {
            this.currentLevel = this.getNextLevel(this.currentLevel);
        }
    }

    private int getNextLevel(int fromLevel) {
        for (int i = fromLevel; i < this.levelCount; i++) {
            if (!this.queues[i].isEmpty()) {
                return i;
            }
        }
        return this.levelCount;
    }
}
