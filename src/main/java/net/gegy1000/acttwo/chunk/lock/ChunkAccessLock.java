package net.gegy1000.acttwo.chunk.lock;

import net.gegy1000.acttwo.lock.Lock;
import net.gegy1000.acttwo.lock.RwLock;

public final class ChunkAccessLock {
    private final Lock readLock;
    private final Lock writeLock;

    public ChunkAccessLock() {
        RwLock lock = new RwLock();
        this.readLock = lock.read();
        this.writeLock = lock.write();
    }

    public Lock read() {
        return this.readLock;
    }

    public Lock write() {
        return this.writeLock;
    }
}
