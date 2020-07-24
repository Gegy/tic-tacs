package net.gegy1000.acttwo.chunk.loader.upgrade;

import net.gegy1000.acttwo.async.lock.JoinLock;
import net.gegy1000.acttwo.async.lock.Lock;
import net.gegy1000.acttwo.chunk.ChunkAccess;
import net.gegy1000.acttwo.chunk.ChunkLockType;
import net.gegy1000.acttwo.chunk.ChunkMap;
import net.gegy1000.acttwo.chunk.entry.ChunkAccessLock;
import net.gegy1000.acttwo.chunk.entry.ChunkEntry;
import net.gegy1000.acttwo.chunk.entry.ChunkEntryState;
import net.gegy1000.acttwo.chunk.step.ChunkRequirement;
import net.gegy1000.acttwo.chunk.step.ChunkRequirements;
import net.gegy1000.acttwo.chunk.step.ChunkStep;
import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Function;

final class AcquireChunks implements Future<AcquireChunks.Result> {
    private final ChunkUpgradeKernel kernel;
    private final ChunkMap chunkMap;

    private final Result result;

    private final Lock[] upgradeLocks;
    private final Lock[] locks;

    private final Lock joinLock;
    private final Future<Unit> acquireJoinLock;

    private ChunkPos pos;
    private ChunkStep currentStep;

    private boolean acquired;

    public AcquireChunks(ChunkUpgradeKernel kernel, ChunkMap chunkMap) {
        this.kernel = kernel;

        this.chunkMap = chunkMap;

        this.upgradeLocks = kernel.create(Lock[]::new);
        this.locks = kernel.create(Lock[]::new);

        this.joinLock = new JoinLock(new Lock[] {
                // TODO: should this join between upgrade locks be at the chunkentry level or at the acquire level?
                new JoinLock(this.upgradeLocks),
                new JoinLock(this.locks)
        });
        this.acquireJoinLock = new Lock.AcquireFuture(this.joinLock);

        this.result = kernel.create(Result::new);
    }

    public void setup(ChunkPos pos, ChunkStep currentStep) {
        this.pos = pos;
        this.currentStep = currentStep;
    }

    private void clearBuffers() {
        Arrays.fill(this.upgradeLocks, null);
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

        if (this.acquireJoinLock.poll(waker) != null) {
            this.acquired = true;
            return this.result;
        } else {
            this.clearBuffers();
            return null;
        }
    }

    private void addUpgradeChunks(ChunkStep step) {
        ChunkAccess chunks = this.chunkMap.visible();

        Lock[] upgradeLocks = this.upgradeLocks;
        Lock[] locks = this.locks;
        ChunkEntryState[] entries = this.result.entries;

        ChunkPos pos = this.pos;
        ChunkUpgradeKernel kernel = this.kernel;

        int radiusForStep = kernel.getRadiusFor(step);

        for (int z = -radiusForStep; z <= radiusForStep; z++) {
            for (int x = -radiusForStep; x <= radiusForStep; x++) {
                ChunkEntry entry = chunks.expectEntry(x + pos.x, z + pos.z);

                if (entry.canUpgradeTo(step)) {
                    int idx = kernel.index(x, z);

                    entries[idx] = entry.getState();

                    upgradeLocks[idx] = entry.getLock().upgrade();
                    locks[idx] = entry.getLock().write(step.getLock());
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
        Lock[] upgradeLocks = this.upgradeLocks;

        int radiusForStep = kernel.getRadiusFor(step);
        for (int z = -radiusForStep; z <= radiusForStep; z++) {
            for (int x = -radiusForStep; x <= radiusForStep; x++) {
                if (upgradeLocks[kernel.index(x, z)] != null) {
                    this.addContextMargin(x, z, requirements);
                }
            }
        }
    }

    private void addContextMargin(int centerX, int centerZ, ChunkRequirements requirements) {
        ChunkAccess chunks = this.chunkMap.visible();

        ChunkEntryState[] entries = this.result.entries;
        Lock[] locks = this.locks;

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

                if (locks[idx] == null) {
                    int distance = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
                    ChunkRequirement requirement = requirements.byDistance(distance);

                    if (requirement != null) {
                        ChunkEntry entry = chunks.expectEntry(x + pos.x, z + pos.z);
                        ChunkAccessLock lock = entry.getLock();

                        ChunkLockType resource = requirement.step.getLock();
                        boolean requireWrite = requirement.write;

                        entries[idx] = entry.getState();
                        locks[idx] = requireWrite ? lock.write(resource) : lock.read(resource);
                    }
                }
            }
        }
    }

    public void release() {
        this.pos = null;
        this.currentStep = null;

        if (this.acquired) {
            this.acquired = false;

            this.joinLock.release();

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
                if (entry != null && AcquireChunks.this.upgradeLocks[i] != null) {
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
