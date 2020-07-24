package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.chunk.ChunkAccess;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.lock.ChunkAccessLock;
import net.gegy1000.acttwo.chunk.step.ChunkRequirement;
import net.gegy1000.acttwo.chunk.step.ChunkRequirements;
import net.gegy1000.acttwo.chunk.step.ChunkStep;
import net.gegy1000.acttwo.lock.JoinLock;
import net.gegy1000.acttwo.lock.Lock;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Function;

final class AcquireChunks implements Future<AcquireChunks.Result> {
    private final ChunkUpgradeKernel kernel;
    private final ChunkMap chunkMap;

    private final Result result;

    private ChunkPos pos;
    private ChunkStep currentStep;

    private final BitSet upgradeChunks;

    private final Lock[] locks;
    private final Future<Unit> joinLock;

    private boolean prepared;
    private boolean acquired;

    public AcquireChunks(ChunkUpgradeKernel kernel, ChunkMap chunkMap) {
        this.kernel = kernel;

        this.chunkMap = chunkMap;
        this.upgradeChunks = kernel.create(BitSet::new);

        this.locks = kernel.create(Lock[]::new);
        this.joinLock = Lock.acquireAsync(new JoinLock(this.locks));

        this.result = kernel.create(Result::new);
    }

    public void prepare(ChunkPos pos, ChunkStep currentStep) {
        this.prepared = true;
        this.pos = pos;
        this.currentStep = currentStep;
    }

    private void clearBuffers() {
        this.upgradeChunks.clear();
        Arrays.fill(this.locks, null);
        Arrays.fill(this.result.entries, null);
    }

    @Nullable
    @Override
    public Result poll(Waker waker) {
        if (this.acquired) {
            return this.result;
        }

        this.addUpgradeChunks(this.currentStep);
        this.addContextChunks(this.currentStep);

        if (this.joinLock.poll(waker) != null) {
            this.acquired = true;
            return this.result;
        } else {
            this.clearBuffers();
            return null;
        }
    }

    private void addUpgradeChunks(ChunkStep step) {
        ChunkAccess chunks = this.chunkMap.visible();

        Lock[] locks = this.locks;
        ChunkEntryState[] entries = this.result.entries;
        BitSet upgradeChunks = this.upgradeChunks;

        ChunkPos pos = this.pos;
        ChunkUpgradeKernel kernel = this.kernel;

        int radiusForStep = kernel.getRadiusFor(step);

        for (int z = -radiusForStep; z <= radiusForStep; z++) {
            for (int x = -radiusForStep; x <= radiusForStep; x++) {
                ChunkEntry entry = chunks.expectEntry(x + pos.x, z + pos.z);

                if (entry.canUpgradeTo(step)) {
                    int idx = kernel.index(x, z);

                    upgradeChunks.set(idx);
                    entries[idx] = entry.getStateUnsafe();
                    locks[idx] = entry.getLock().write();
                }
            }
        }
    }

    private void addContextChunks(ChunkStep step) {
        ChunkRequirements requirements = step.getRequirements();
        if (requirements.getRadius() <= 0) {
            return;
        }

        ChunkUpgradeKernel kernel = this.kernel;
        BitSet upgradeChunks = this.upgradeChunks;

        int radiusForStep = kernel.getRadiusFor(step);
        for (int z = -radiusForStep; z <= radiusForStep; z++) {
            for (int x = -radiusForStep; x <= radiusForStep; x++) {
                if (upgradeChunks.get(kernel.index(x, z))) {
                    this.addContextMargin(x, z, requirements);
                }
            }
        }
    }

    private void addContextMargin(int centerX, int centerZ, ChunkRequirements requirements) {
        ChunkAccess chunks = this.chunkMap.visible();

        ChunkEntryState[] entries = this.result.entries;
        Lock[] locks = this.locks;
        BitSet upgradeChunks = this.upgradeChunks;

        ChunkPos pos = this.pos;
        ChunkUpgradeKernel kernel = this.kernel;

        int kernelRadius = kernel.getRadius();
        int contextRadius = requirements.getRadius();

        int minX = Math.max(centerX - contextRadius, -kernelRadius);
        int maxX = Math.min(centerX + contextRadius, kernelRadius);
        int minZ = Math.max(centerZ - contextRadius, -kernelRadius);
        int maxZ = Math.min(centerZ + contextRadius, kernelRadius);

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int idx = kernel.index(x, z);

                if (locks[idx] == null && !upgradeChunks.get(idx)) {
                    int distance = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
                    ChunkRequirement requirement = requirements.byDistance(distance);

                    if (requirement != null) {
                        ChunkEntry entry = chunks.expectEntry(x + pos.x, z + pos.z);
                        ChunkAccessLock lock = entry.getLock();

                        boolean requireWrite = requirement.write;

                        entries[idx] = entry.getStateUnsafe();
                        locks[idx] = requireWrite ? lock.write() : lock.read();
                    }
                }
            }
        }
    }

    public boolean isPrepared() {
        return this.prepared;
    }

    public void release() {
        this.prepared = false;

        this.pos = null;
        this.currentStep = null;

        if (this.acquired) {
            this.acquired = false;

            for (Lock lock : this.locks) {
                if (lock != null) {
                    lock.release();
                }
            }

            this.clearBuffers();
        }
    }

    public final class Result {
        final ChunkEntryState[] entries;

        Result(int bufferSize) {
            this.entries = new ChunkEntryState[bufferSize];
        }

        public <T> void openUpgradeTasks(Future<T>[] tasks, Function<ChunkEntryState, Future<T>> function) {
            ChunkEntryState[] entries = this.entries;

            for (int i = 0; i < entries.length; i++) {
                ChunkEntryState entry = entries[i];
                if (entry != null && AcquireChunks.this.upgradeChunks.get(i)) {
                    tasks[i] = function.apply(entry);
                }
            }
        }

        @Nullable
        public ChunkEntryState getEntry(int x, int z) {
            int idx = AcquireChunks.this.kernel.index(x, z);
            return this.entries[idx];
        }
    }
}
