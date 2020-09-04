package net.gegy1000.tictacs.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.async.LinkedWaiter;
import net.gegy1000.tictacs.async.WaiterQueue;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.mixin.TacsAccessor;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class ChunkMap {
    private final ServerWorld world;
    private final ChunkController controller;

    private final Long2ObjectMap<ChunkEntry> primaryEntries = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<ChunkEntry> visibleEntries = new Long2ObjectOpenHashMap<>();

    private Long2ObjectMap<ChunkEntry> pendingUpdates = new Long2ObjectOpenHashMap<>();

    private final AtomicInteger writeState = new AtomicInteger();
    private final Lock writeLock = new ReentrantLock();

    private final AtomicInteger flushCount = new AtomicInteger();

    private final ChunkAccess primary = new Primary();
    private final ChunkAccess visible = new Visible();

    private final WaiterQueue flushWaiters = new WaiterQueue();

    public ChunkMap(ServerWorld world, ChunkController controller) {
        this.world = world;
        this.controller = controller;
    }

    public ChunkEntry getOrCreateEntry(ChunkPos pos, int level) {
        ChunkEntry entry = this.primary.getEntry(pos);

        if (entry == null) {
            TacsAccessor accessor = (TacsAccessor) this.controller;
            entry = (ChunkEntry) accessor.getUnloadingChunks().remove(pos.toLong());

            if (entry != null) {
                entry.setLevel(level);
            }
        }

        if (entry == null) {
            entry = this.createEntry(pos, level);
            this.primary.putEntry(entry);
        }

        return entry;
    }

    private ChunkEntry createEntry(ChunkPos pos, int level) {
        ThreadedAnvilChunkStorage tacs = this.controller.asTacs();
        TacsAccessor accessor = (TacsAccessor) this.controller;

        ChunkEntry unloadingEntry = (ChunkEntry) accessor.getUnloadingChunks().remove(pos.toLong());
        if (unloadingEntry != null) {
            unloadingEntry.setLevel(level);
            return unloadingEntry;
        }

        return new ChunkEntry(pos, level, this.world.getLightingProvider(), accessor.getChunkTaskPrioritySystem(), tacs);
    }

    public FlushListener awaitFlush() {
        return new FlushListener(this.flushCount.get());
    }

    public ChunkAccess primary() {
        return this.primary;
    }

    public ChunkAccess visible() {
        return this.visible;
    }

    public boolean flushToVisible() {
        if (!this.pendingUpdates.isEmpty()) {
            Long2ObjectMap<ChunkEntry> pendingUpdates = this.pendingUpdates;
            this.pendingUpdates = new Long2ObjectOpenHashMap<>();

            try {
                this.lockWrite();

                for (Long2ObjectMap.Entry<ChunkEntry> update : Long2ObjectMaps.fastIterable(pendingUpdates)) {
                    long pos = update.getLongKey();
                    ChunkEntry entry = update.getValue();

                    if (entry != null) {
                        this.visibleEntries.put(pos, entry);
                    } else {
                        this.visibleEntries.remove(pos);
                    }
                }
            } finally {
                this.unlockWrite();
            }

            this.notifyFlush();

            return true;
        }

        return false;
    }

    private void notifyFlush() {
        this.flushCount.getAndIncrement();
        this.flushWaiters.wake();
    }

    public int getEntryCount() {
        return this.primaryEntries.size();
    }

    void lockWrite() {
        this.writeLock.lock();
        this.writeState.getAndIncrement();
    }

    void unlockWrite() {
        this.writeLock.unlock();
        this.writeState.getAndIncrement();
    }

    static boolean isWriting(int state) {
        // between lockWrite() and unlockWrite(), the state will always be odd
        return (state & 1) == 1;
    }

    int waitForRead() {
        while (true) {
            int writeState = this.writeState.get();
            if (isWriting(writeState)) {
                // wait for the writer to unlock
                this.writeLock.lock();
                this.writeLock.unlock();
                continue;
            }
            return writeState;
        }
    }

    boolean isStateValid(int state) {
        return this.writeState.get() == state;
    }

    final class Primary implements ChunkAccess {
        @Override
        public void putEntry(ChunkEntry entry) {
            long pos = entry.getPos().toLong();
            ChunkMap.this.primaryEntries.put(pos, entry);
            ChunkMap.this.pendingUpdates.put(pos, entry);
        }

        @Override
        public ChunkEntry removeEntry(long pos) {
            ChunkEntry entry = ChunkMap.this.primaryEntries.remove(pos);
            if (entry != null) {
                ChunkMap.this.pendingUpdates.put(pos, null);
            }
            return entry;
        }

        @Nullable
        @Override
        public ChunkEntry getEntry(long pos) {
            return ChunkMap.this.primaryEntries.get(pos);
        }

        @Override
        public ObjectCollection<ChunkEntry> getEntries() {
            return ChunkMap.this.primaryEntries.values();
        }
    }

    final class Visible implements ChunkAccess {
        @Override
        public void putEntry(ChunkEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChunkEntry removeEntry(long pos) {
            throw new UnsupportedOperationException();
        }

        @Nullable
        @Override
        public ChunkEntry getEntry(long pos) {
            int state;
            ChunkEntry entry;

            // wait for read to be available and get element, but ensure nothing has changed before we return
            do {
                state = ChunkMap.this.waitForRead();
                entry = ChunkMap.this.visibleEntries.get(pos);
            } while (!ChunkMap.this.isStateValid(state));

            return entry;
        }

        @Override
        public ObjectCollection<ChunkEntry> getEntries() {
            try {
                ChunkMap.this.writeLock.lock();
                return new ObjectArrayList<>(ChunkMap.this.visibleEntries.values());
            } finally {
                ChunkMap.this.writeLock.unlock();
            }
        }
    }

    public class FlushListener extends LinkedWaiter implements Future<Unit> {
        private final int flushCount;

        FlushListener(int flushCount) {
            this.flushCount = flushCount;
        }

        @Nullable
        @Override
        public Unit poll(Waker waker) {
            if (this.isReady()) {
                return Unit.INSTANCE;
            }

            ChunkMap.this.flushWaiters.registerWaiter(this, waker);

            if (this.isReady()) {
                this.invalidateWaker();
                return Unit.INSTANCE;
            }

            return null;
        }

        private boolean isReady() {
            return ChunkMap.this.flushCount.get() > this.flushCount;
        }
    }
}
