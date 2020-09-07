package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.tictacs.TicTacs;
import net.gegy1000.tictacs.chunk.step.ChunkStep;

import java.util.List;
import java.util.function.IntFunction;

public final class ChunkUpgradeKernel {
    private static final ChunkUpgradeKernel[] FOR_STEP = new ChunkUpgradeKernel[ChunkStep.STEPS.size()];

    static {
        List<ChunkStep> steps = ChunkStep.STEPS;
        for (int i = 0; i < steps.size(); i++) {
            FOR_STEP[i] = new ChunkUpgradeKernel(steps.get(i));
        }
    }

    private final ChunkStep focus;
    private final int radius;
    private final int size;

    private ChunkUpgradeKernel(ChunkStep focus) {
        this.focus = focus;
        this.radius = ChunkStep.getRequiredRadius(focus);
        this.size = this.radius * 2 + 1;
    }

    public static ChunkUpgradeKernel forStep(ChunkStep step) {
        return FOR_STEP[step.getIndex()];
    }

    public int getRadius() {
        return this.radius;
    }

    public int getRadiusFor(ChunkStep step) {
        return ChunkStep.getDistanceFromFull(step) - ChunkStep.getDistanceFromFull(this.focus);
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
