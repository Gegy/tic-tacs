package net.gegy1000.tictacs.chunk.entry;

import net.gegy1000.tictacs.chunk.ChunkLockType;
import net.gegy1000.tictacs.async.lock.JoinLock;
import net.gegy1000.tictacs.async.lock.Lock;
import net.gegy1000.tictacs.async.lock.Mutex;
import net.gegy1000.tictacs.async.lock.RwLock;

import java.util.Arrays;

public final class ChunkAccessLock {
    private static final ChunkLockType[] RESOURCES = ChunkLockType.values();

    private final Lock[] readLocks;
    private final Lock[] writeLocks;

    private final Lock upgradeLock;

    public ChunkAccessLock() {
        this.readLocks = new Lock[RESOURCES.length];
        this.writeLocks = new Lock[RESOURCES.length];

        for (int i = 0; i < RESOURCES.length; i++) {
            RwLock resourceLock = new RwLock();

            this.readLocks[i] = resourceLock.read();
            this.writeLocks[i] = resourceLock.write();
        }

        this.upgradeLock = new Mutex();
    }

    public Lock upgrade() {
        return this.upgradeLock;
    }

    public Lock read(ChunkLockType resource) {
        return this.readLocks[resource.ordinal()];
    }

    public Lock write(ChunkLockType resource) {
        return this.writeLocks[resource.ordinal()];
    }

    public Lock lockAll() {
        Lock[] locks = Arrays.copyOf(this.writeLocks, this.writeLocks.length + 1);
        locks[locks.length - 1] = this.upgradeLock;

        return new JoinLock(locks);
    }
}
