package net.gegy1000.acttwo.chunk.step;

import java.util.List;

final class StepKernelResolver {
    private final List<ChunkStep> steps;

    StepKernelResolver(List<ChunkStep> steps) {
        this.steps = steps;
    }

    private int effectiveRadiusFor(ChunkStep step) {
        ChunkRequirements requirements = step.getRequirements();

        int radius = requirements.getRadius();
        if (radius < 0) {
            return -1;
        }

        int effectiveRadius = radius;

        for (int distance = 0; distance <= radius; distance++) {
            ChunkRequirement requirement = requirements.byDistance(distance);
            if (requirement != null) {
                int childRadius = this.effectiveRadiusFor(requirement.step);
                if (childRadius >= 0) {
                    effectiveRadius = Math.max(effectiveRadius, distance + childRadius);
                }
            }
        }

        return effectiveRadius;
    }

    private void resolveRadii(Results results) {
        results.stepToRadius = new int[this.steps.size()];

        for (ChunkStep step : this.steps) {
            int radius = this.effectiveRadiusFor(step);
            results.stepToRadius[step.getIndex()] = radius;

            results.maxDistance = Math.max(radius, results.maxDistance);
        }
    }

    private void tryAddStepAt(ChunkStep step, int distance, Results results) {
        ChunkRequirements requirements = step.getRequirements();

        ChunkStep existingStep = results.distanceToStep[distance];
        if (existingStep == null || step.greaterThan(existingStep)) {
            results.distanceToStep[distance] = step;
        }

        int radius = requirements.getRadius();
        for (int offset = 0; offset <= radius; offset++) {
            ChunkRequirement requirement = requirements.byDistance(offset);
            if (requirement != null) {
                this.tryAddStepAt(requirement.step, distance + offset, results);
            }
        }
    }

    private void resolveDistances(Results results) {
        results.distanceToStep = new ChunkStep[results.maxDistance + 1];

        ChunkStep lastStep = this.steps.get(this.steps.size() - 1);
        this.tryAddStepAt(lastStep, 0, results);

        results.stepToDistance = new int[this.steps.size()];

        int distance = 0;
        for (int i = this.steps.size() - 1; i >= 0; i--) {
            ChunkStep step = this.steps.get(i);

            while (distance + 1 <= results.maxDistance && step.lessOrEqual(results.distanceToStep[distance + 1])) {
                distance++;
            }

            results.stepToDistance[i] = distance;
        }
    }

    public Results resolve() {
        Results results = new Results();

        this.resolveRadii(results);
        this.resolveDistances(results);

        return results;
    }

    static final class Results {
        public int maxDistance;
        public int[] stepToRadius;
        public ChunkStep[] distanceToStep;
        public int[] stepToDistance;
    }
}
