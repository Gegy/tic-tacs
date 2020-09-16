package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.tictacs.TicTacs;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.gegy1000.tictacs.chunk.step.StepKernelResolver;

import java.util.List;
import java.util.function.IntFunction;

public final class ChunkUpgradeKernel {
    private static final List<ChunkStep> STEPS = ChunkStep.STEPS;
    private static final int STEP_COUNT = STEPS.size();

    private static final ChunkUpgradeKernel[] BETWEEN_STEPS = new ChunkUpgradeKernel[STEP_COUNT * STEP_COUNT];

    static {
        // TODO: this table can be more compact by not having null entries
        for (int fromIdx = 0; fromIdx < STEP_COUNT; fromIdx++) {
            ChunkStep from = ChunkStep.byIndex(fromIdx - 1);

            for (int toIdx = fromIdx - 1; toIdx < STEP_COUNT; toIdx++) {
                ChunkStep to = ChunkStep.byIndex(toIdx);
                if (to == null) {
                    continue;
                }

                BETWEEN_STEPS[toIdx + fromIdx * STEP_COUNT] = new ChunkUpgradeKernel(from, to);
            }
        }
    }

    private final ChunkStep from;
    private final ChunkStep to;
    private final int radius;
    private final int size;

    private ChunkUpgradeKernel(ChunkStep from, ChunkStep to) {
        if (to.lessThan(from)) {
            throw new IllegalArgumentException(from + " > " + to);
        }

        this.from = from;
        this.to = to;

        this.radius = to != from ? StepKernelResolver.effectiveRadiusFor(to, from) : 0;
        this.size = this.radius * 2 + 1;
    }

    public static ChunkUpgradeKernel forStep(ChunkStep step) {
        return BETWEEN_STEPS[step.getIndex()];
    }

    public static ChunkUpgradeKernel betweenSteps(ChunkStep from, ChunkStep to) {
        if (to.lessThan(from)) {
            throw new IllegalArgumentException(from + " > " + to);
        }

        int fromIdx = from != null ? from.getIndex() + 1 : 0;
        int toIdx = to.getIndex();
        return BETWEEN_STEPS[toIdx + fromIdx * STEP_COUNT];
    }

    public int getRadius() {
        return this.radius;
    }

    public int getRadiusFor(ChunkStep step) {
        return ChunkStep.getDistanceFromFull(step) - ChunkStep.getDistanceFromFull(this.to);
    }

    public int getSize() {
        return this.size;
    }

    public int index(int x, int z) {
        int radius = this.radius;
        if (TicTacs.DEBUG) {
            if (x < -radius || z < -radius || x > radius || z > radius) {
                throw new IllegalArgumentException("[" + x + "; " + z + "] out of radius=" + this.radius);
            }
        }
        return (x + radius) + (z + radius) * this.size;
    }

    public <T> T create(IntFunction<T> function) {
        return function.apply(this.size * this.size);
    }
}
