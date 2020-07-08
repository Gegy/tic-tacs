package net.gegy1000.acttwo.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.tracker.ChunkQueues;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class ChunkMap {
    private final ServerWorld world;
    private final ChunkController controller;
    private final ChunkQueues queues;

    private final Long2ObjectMap<ChunkEntry> primaryEntries = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<ChunkEntry> visibleEntries = new Long2ObjectOpenHashMap<>();

    private Long2ObjectMap<ChunkEntry> pendingUpdates = new Long2ObjectOpenHashMap<>();

    private final AtomicInteger writeState = new AtomicInteger();
    private final Lock writeLock = new ReentrantLock();

    private final ChunkAccess primary = new Primary();
    private final ChunkAccess visible = new Visible();

    private final LongSet fullChunks = new LongOpenHashSet();
    private final AtomicInteger tickingChunksLoaded = new AtomicInteger();

    private final AtomicReference<FlushListener> flushListener = new AtomicReference<>();

    public ChunkMap(ServerWorld world, ChunkController controller) {
        this.world = world;
        this.controller = controller;
        this.queues = new ChunkQueues(this);
    }

    public ChunkEntry createEntry(ChunkPos pos, int level) {
        return new ChunkEntry(pos, level, this.world.getLightingProvider(), this.controller.tracker);
    }

    public FlushListener awaitFlush() {
        FlushListener listener = new FlushListener();

        while (true) {
            FlushListener root = this.flushListener.get();
            listener.previous = root;

            if (this.flushListener.compareAndSet(root, listener)) {
                return listener;
            }
        }
    }

    public ChunkAccess primary() {
        return this.primary;
    }

    public ChunkAccess visible() {
        return this.visible;
    }

    public boolean tryAddFullChunk(ChunkPos pos) {
        return this.fullChunks.add(pos.toLong());
    }

    public boolean tryRemoveFullChunk(ChunkPos pos) {
        return this.fullChunks.remove(pos.toLong());
    }

    public void incrementTickingChunksLoaded() {
        this.tickingChunksLoaded.getAndIncrement();
    }

    public ChunkQueues getQueues() {
        return this.queues;
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
        FlushListener listener = this.flushListener.getAndSet(null);
        if (listener != null) {
            listener.notifyFlush();
        }
    }

    public int getEntryCount() {
        return this.primaryEntries.size();
    }

    public int getTickingChunksLoaded() {
        return this.tickingChunksLoaded.get();
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

    class Primary implements ChunkAccess {
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
        public Collection<ChunkEntry> getEntries() {
            return ChunkMap.this.primaryEntries.values();
        }
    }

    class Visible implements ChunkAccess {
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
        public Collection<ChunkEntry> getEntries() {
            try {
                ChunkMap.this.writeLock.lock();
                return new ArrayList<>(ChunkMap.this.visibleEntries.values());
            } finally {
                ChunkMap.this.writeLock.unlock();
            }
        }
    }

    public static class FlushListener implements Future<Unit> {
        volatile Waker waker;
        volatile FlushListener previous;

        volatile boolean flushed;

        @Nullable
        @Override
        public Unit poll(Waker waker) {
            this.waker = waker;
            return this.flushed ? Unit.INSTANCE : null;
        }

        public void invalidate() {
            this.waker = null;
        }

        void notifyFlush() {
            this.flushed = true;

            FlushListener previous = this.previous;
            if (previous != null) {
                previous.notifyFlush();
            }

            Waker waker = this.waker;
            if (waker != null) {
                waker.wake();
            }

            this.previous = null;
            this.waker = null;
        }
    }
}
