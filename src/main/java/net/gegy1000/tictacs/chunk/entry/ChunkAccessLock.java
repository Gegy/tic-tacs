package net.gegy1000.tictacs.chunk.entry;

import net.gegy1000.tictacs.async.lock.JoinLock;
import net.gegy1000.tictacs.async.lock.Lock;
import net.gegy1000.tictacs.async.lock.Mutex;
import net.gegy1000.tictacs.async.lock.NullLock;
import net.gegy1000.tictacs.async.lock.RwLock;
import net.gegy1000.tictacs.chunk.ChunkLockType;
import net.gegy1000.tictacs.config.TicTacsConfig;

import java.util.Arrays;

public final class ChunkAccessLock {
    private static final ChunkLockType[] RESOURCES = ChunkLockType.values();

    private final Lock[] readLocks;
    private final Lock[] writeLocks;

    private final Lock upgradeLock;

    private volatile Lock allLock;

    public ChunkAccessLock() {
        this.readLocks = new Lock[RESOURCES.length];
        this.writeLocks = new Lock[RESOURCES.length];

        if (TicTacsConfig.get().isSingleThreaded()) {
            for (int i = 0; i < RESOURCES.length; i++) {
                this.readLocks[i] = NullLock.INSTANCE;
                this.writeLocks[i] = NullLock.INSTANCE;
            }
        } else {
            for (int i = 0; i < RESOURCES.length; i++) {
                RwLock resourceLock = new RwLock();

                this.readLocks[i] = resourceLock.read();
                this.writeLocks[i] = resourceLock.write();
            }
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
        if (this.allLock == null) {
            Lock[] locks = Arrays.copyOf(this.writeLocks, this.writeLocks.length + 1);
            locks[locks.length - 1] = this.upgradeLock;

            this.allLock = new JoinLock(locks);
        }

        return this.allLock;
    }
}
