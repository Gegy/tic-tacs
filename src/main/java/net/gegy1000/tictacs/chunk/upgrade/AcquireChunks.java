package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.AtomicPool;
import net.gegy1000.tictacs.async.lock.JoinLock;
import net.gegy1000.tictacs.async.lock.Lock;
import net.gegy1000.tictacs.chunk.ChunkLockType;
import net.gegy1000.tictacs.chunk.entry.ChunkAccessLock;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.step.ChunkRequirement;
import net.gegy1000.tictacs.chunk.step.ChunkRequirements;
import net.gegy1000.tictacs.chunk.step.ChunkStep;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Function;

final class AcquireChunks {
    private static final AtomicPool<AcquireChunks>[] STEP_TO_POOL = initPools();

    @SuppressWarnings("unchecked")
    private static AtomicPool<AcquireChunks>[] initPools() {
        int poolCapacity = 512;

        AtomicPool<AcquireChunks>[] stepToPool = new AtomicPool[ChunkStep.STEPS.size()];
        for (int i = 0; i < stepToPool.length; i++) {
            ChunkStep step = ChunkStep.byIndex(i);
            stepToPool[i] = new AtomicPool<>(poolCapacity, () -> new AcquireChunks(step));
        }

        return stepToPool;
    }

    private final ChunkStep targetStep;
    private final ChunkUpgradeKernel kernel;

    private final Result result;

    private final Lock[] upgradeLocks;
    private final Lock[] locks;

    private final Lock joinLock;
    private final Future<Unit> acquireJoinLock;

    volatile ChunkUpgradeEntries entries;
    volatile boolean acquired;

    private AcquireChunks(ChunkStep targetStep) {
        this.targetStep = targetStep;

        this.kernel = ChunkUpgradeKernel.forStep(targetStep);
        this.upgradeLocks = this.kernel.create(Lock[]::new);
        this.locks = this.kernel.create(Lock[]::new);

        this.joinLock = new JoinLock(new Lock[] {
                new JoinLock(this.upgradeLocks),
                new JoinLock(this.locks)
        });
        this.acquireJoinLock = new Lock.AcquireFuture(this.joinLock);

        this.result = new Result();
    }

    private static AtomicPool<AcquireChunks> poolFor(ChunkStep step) {
        return STEP_TO_POOL[step.getIndex()];
    }

    public static AcquireChunks open(ChunkUpgradeEntries entries, ChunkStep step) {
        AcquireChunks acquire = poolFor(step).acquire();
        acquire.entries = entries;
        return acquire;
    }

    private void clearBuffers() {
        Arrays.fill(this.upgradeLocks, null);
        Arrays.fill(this.locks, null);

        this.result.empty = true;
        this.result.unloaded = false;
    }

    @Nullable
    public Result poll(Waker waker, ChunkStep step) {
        if (this.acquired) {
            return this.result;
        }

        this.clearBuffers();
        this.acquired = false;

        if (this.collectChunks(step)) {
            this.result.empty = false;
        } else {
            this.result.empty = true;
            this.acquired = true;
            return this.result;
        }

        if (this.acquireJoinLock.poll(waker) != null) {
            this.acquired = true;
            return this.result;
        } else {
            return null;
        }
    }

    private boolean collectChunks(ChunkStep step) {
        Lock[] upgradeLocks = this.upgradeLocks;
        Lock[] locks = this.locks;

        ChunkUpgradeEntries entries = this.entries;
        ChunkUpgradeKernel kernel = this.kernel;
        int radiusForStep = kernel.getRadiusFor(step);

        ChunkRequirements requirements = step.getRequirements();

        boolean added = false;

        for (int z = -radiusForStep; z <= radiusForStep; z++) {
            for (int x = -radiusForStep; x <= radiusForStep; x++) {
                ChunkEntry entry = entries.getEntry(x, z);
                if (!entry.isValidAs(step)) {
                    // we need to release all the locks that we've acquired so far
                    this.joinLock.release();
                    this.clearBuffers();

                    this.result.unloaded = true;

                    return false;
                }

                if (entry.canUpgradeTo(step)) {
                    entry.trySpawnUpgradeTo(step);

                    int idx = kernel.index(x, z);
                    upgradeLocks[idx] = entry.getLock().upgrade();
                    locks[idx] = entry.getLock().write(step.getLock());

                    this.collectContextMargin(x, z, requirements);

                    added = true;
                }
            }
        }

        return added;
    }

    private void collectContextMargin(int centerX, int centerZ, ChunkRequirements requirements) {
        int contextRadius = requirements.getRadius();
        if (contextRadius <= 0) {
            return;
        }

        Lock[] locks = this.locks;
        ChunkUpgradeEntries entries = this.entries;
        ChunkUpgradeKernel kernel = this.kernel;

        int kernelRadius = kernel.getRadius();

        int minX = Math.max(centerX - contextRadius, -kernelRadius);
        int maxX = Math.min(centerX + contextRadius, kernelRadius);
        int minZ = Math.max(centerZ - contextRadius, -kernelRadius);
        int maxZ = Math.min(centerZ + contextRadius, kernelRadius);

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int idx = kernel.index(x, z);

                if (locks[idx] == null) {
                    int distance = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
                    ChunkRequirement requirement = requirements.byDistance(distance);

                    if (requirement != null) {
                        ChunkEntry entry = entries.getEntry(x, z);
                        ChunkAccessLock lock = entry.getLock();

                        ChunkLockType resource = requirement.step.getLock();
                        boolean requireWrite = requirement.write;

                        locks[idx] = requireWrite ? lock.write(resource) : lock.read(resource);
                    }
                }
            }
        }
    }

    public void release() {
        if (this.acquired) {
            this.acquired = false;

            this.joinLock.release();

            this.clearBuffers();
        }

        this.entries = null;

        poolFor(this.targetStep).release(this);
    }

    public final class Result {
        boolean empty = true;
        boolean unloaded = false;

        public <T> void openUpgradeTasks(Future<T>[] tasks, Function<ChunkEntry, Future<T>> function) {
            Lock[] upgradeLocks = AcquireChunks.this.upgradeLocks;
            ChunkUpgradeEntries entries = AcquireChunks.this.entries;
            ChunkUpgradeKernel kernel = AcquireChunks.this.kernel;
            int radius = kernel.getRadius();

            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    int idx = kernel.index(x, z);
                    if (upgradeLocks[idx] != null) {
                        tasks[idx] = function.apply(entries.getEntry(x, z));
                    }
                }
            }
        }
    }
}
