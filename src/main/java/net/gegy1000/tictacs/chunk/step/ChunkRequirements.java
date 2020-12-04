package net.gegy1000.tictacs.chunk.step;

import org.jetbrains.annotations.Nullable;
import java.util.Arrays;

public final class ChunkRequirements {
    private ChunkRequirement[] byDistance = new ChunkRequirement[0];
    private int radius;

    private ChunkRequirements() {
    }

    public static ChunkRequirements none() {
        return new ChunkRequirements();
    }

    public static ChunkRequirements from(ChunkStep step) {
        return new ChunkRequirements().write(step, 0);
    }

    public ChunkRequirements read(ChunkStep step, int radius) {
        return this.add(ChunkRequirement.read(step), radius);
    }

    public ChunkRequirements write(ChunkStep step, int radius) {
        return this.add(ChunkRequirement.write(step), radius);
    }

    public ChunkRequirements require(ChunkStep step, int radius) {
        return this.add(ChunkRequirement.require(step), radius);
    }

    public ChunkRequirements add(ChunkRequirement requirement, int radius) {
        this.ensureRadius(radius);

        for (int i = 0; i <= radius; i++) {
            ChunkRequirement existing = this.byDistance[i];
            this.byDistance[i] = ChunkRequirement.merge(existing, requirement);
        }

        return this;
    }

    private void ensureRadius(int radius) {
        if (this.byDistance.length <= radius) {
            this.byDistance = Arrays.copyOf(this.byDistance, radius + 1);
            this.radius = Math.max(this.byDistance.length - 1, 0);
        }
    }

    @Nullable
    public ChunkRequirement byDistance(int distance) {
        distance = Math.max(distance, 0);

        if (distance >= this.byDistance.length) {
            return null;
        }

        return this.byDistance[distance];
    }

    public int getRadius() {
        return this.radius;
    }
}
