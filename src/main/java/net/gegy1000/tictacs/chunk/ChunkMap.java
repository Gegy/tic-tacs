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

import org.jetbrains.annotations.Nullable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkMap {
    private final ServerWorld world;
    private final ChunkController controller;

    private final Long2ObjectMap<ChunkEntry> primaryEntries = new Long2ObjectOpenHashMap<>();
    private volatile Long2ObjectMap<ChunkEntry> visibleEntries = new Long2ObjectOpenHashMap<>();
    private Long2ObjectMap<ChunkEntry> swapEntries = new Long2ObjectOpenHashMap<>();

    private Long2ObjectMap<ChunkEntry> pendingUpdates = new Long2ObjectOpenHashMap<>();

    private final AtomicInteger flushCount = new AtomicInteger();

    private final ChunkAccess primary = new Primary();
    private final ChunkAccess visible = new Visible();

    private final ChunkTickingMaps tickingMaps = new ChunkTickingMaps();

    private final WaiterQueue flushWaiters = new WaiterQueue();

    private ChunkMapListener[] listeners = new ChunkMapListener[0];

    public ChunkMap(ServerWorld world, ChunkController controller) {
        this.world = world;
        this.controller = controller;

        this.addListener(this.tickingMaps);
    }

    public void addListener(ChunkMapListener listener) {
        this.listeners = Arrays.copyOf(this.listeners, this.listeners.length + 1);
        this.listeners[this.listeners.length - 1]  = listener;
    }

    public ChunkEntry getOrCreateEntry(long pos, int level) {
        ChunkEntry entry = this.primary.getEntry(pos);
        if (entry != null) {
            return entry;
        } else {
            return this.loadEntry(pos, level);
        }
    }

    public ChunkEntry loadEntry(long pos, int level) {
        ChunkEntry entry = this.createEntry(pos, level);
        this.primary.putEntry(entry);
        return entry;
    }

    private ChunkEntry createEntry(long pos, int level) {
        ThreadedAnvilChunkStorage tacs = this.controller.asTacs();
        TacsAccessor accessor = (TacsAccessor) this.controller;

        ChunkEntry unloadingEntry = (ChunkEntry) accessor.getUnloadingChunks().remove(pos);
        if (unloadingEntry != null) {
            unloadingEntry.setLevel(level);
            return unloadingEntry;
        }

        return new ChunkEntry(new ChunkPos(pos), level, this.world.getLightingProvider(), accessor.getChunkTaskPrioritySystem(), tacs);
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
            Long2ObjectMap<ChunkEntry> pendingUpdates = this.takePendingUpdates();

            // prepare the new entry map before swapping
            Long2ObjectMap<ChunkEntry> swapEntries = this.swapEntries;
            this.applyPendingUpdatesTo(pendingUpdates, swapEntries);

            // swap the entry maps
            this.swapEntries = this.visibleEntries;
            this.visibleEntries = swapEntries;

            // now we can safely apply the pending updates to the swap map
            this.applyPendingUpdatesTo(pendingUpdates, this.swapEntries);

            this.notifyFlush();

            return true;
        }

        return false;
    }

    private void applyPendingUpdatesTo(Long2ObjectMap<ChunkEntry> pending, Long2ObjectMap<ChunkEntry> entries) {
        for (Long2ObjectMap.Entry<ChunkEntry> update : Long2ObjectMaps.fastIterable(pending)) {
            long pos = update.getLongKey();
            ChunkEntry entry = update.getValue();

            if (entry != null) {
                entries.put(pos, entry);
            } else {
                entries.remove(pos);
            }
        }
    }

    private Long2ObjectMap<ChunkEntry> takePendingUpdates() {
        Long2ObjectMap<ChunkEntry> pendingUpdates = this.pendingUpdates;
        this.pendingUpdates = new Long2ObjectOpenHashMap<>();
        return pendingUpdates;
    }

    private void notifyFlush() {
        this.flushCount.getAndIncrement();
        this.flushWaiters.wake();
    }

    public int getEntryCount() {
        return this.primaryEntries.size();
    }

    public ChunkTickingMaps getTickingMaps() {
        return this.tickingMaps;
    }

    private void onAddChunk(ChunkEntry entry) {
        for (ChunkMapListener listener : this.listeners) {
            listener.onAddChunk(entry);
        }
    }

    private void onRemoveChunk(ChunkEntry entry) {
        for (ChunkMapListener listener : this.listeners) {
            listener.onRemoveChunk(entry);
        }
    }

    final class Primary implements ChunkAccess {
        @Override
        public void putEntry(ChunkEntry entry) {
            long pos = entry.getPos().toLong();
            ChunkMap.this.primaryEntries.put(pos, entry);
            ChunkMap.this.pendingUpdates.put(pos, entry);
            ChunkMap.this.onAddChunk(entry);
        }

        @Override
        public ChunkEntry removeEntry(long pos) {
            ChunkEntry entry = ChunkMap.this.primaryEntries.remove(pos);
            if (entry != null) {
                ChunkMap.this.onRemoveChunk(entry);
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
            return ChunkMap.this.visibleEntries.get(pos);
        }

        @Override
        public ObjectCollection<ChunkEntry> getEntries() {
            return new ObjectArrayList<>(ChunkMap.this.visibleEntries.values());
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
