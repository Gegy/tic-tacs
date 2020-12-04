package net.gegy1000.tictacs.chunk;

import it.unimi.dsi.fastutil.HashCommon;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;

import org.jetbrains.annotations.Nullable;
import java.util.Arrays;

public final class LossyChunkCache {
    private static final int COORD_BITS = 30;
    private static final int COORD_MASK = (1 << COORD_BITS) - 1;
    private static final int STEP_BITS = 4;
    private static final int STEP_MASK = (1 << STEP_BITS) - 1;

    private final int mask;

    private final long[] keys;
    private final Chunk[] values;

    public LossyChunkCache(int capacity) {
        capacity = MathHelper.smallestEncompassingPowerOfTwo(capacity);
        this.mask = capacity - 1;

        this.keys = new long[capacity];
        this.values = new Chunk[capacity];
    }

    public void clear() {
        Arrays.fill(this.keys, Long.MIN_VALUE);
        Arrays.fill(this.values, null);
    }

    public void put(int x, int z, ChunkStep step, Chunk chunk) {
        if (chunk == null) {
            return;
        }

        long key = key(x, z, step);
        int index = this.index(key);

        this.keys[index] = key;
        this.values[index] = chunk;
    }

    @Nullable
    public Chunk get(int x, int z, ChunkStep step) {
        long key = key(x, z, step);
        int index = this.index(key);

        if (this.keys[index] == key) {
            return this.values[index];
        }

        return null;
    }

    private static long key(int x, int z, ChunkStep step) {
        return (long) (x & COORD_MASK) << 34
                | (long) (z & COORD_MASK) << 4
                | (step.getIndex() & STEP_MASK);
    }

    private int index(long key) {
        return (int) HashCommon.mix(key) & this.mask;
    }
}
