package net.gegy1000.tictacs.chunk.step;

public final class ChunkRequirement {
    public final ChunkStep step;
    public final boolean write;

    ChunkRequirement(ChunkStep step, boolean write) {
        this.step = step;
        this.write = write;
    }

    public static ChunkRequirement write(ChunkStep step) {
        return new ChunkRequirement(step, true);
    }

    public static ChunkRequirement read(ChunkStep step) {
        return new ChunkRequirement(step, false);
    }

    public static ChunkRequirement merge(ChunkRequirement a, ChunkRequirement b) {
        if (a == null) return b;
        if (b == null) return a;

        ChunkStep step = ChunkStep.max(a.step, b.step);
        boolean write = a.write || b.write;

        return new ChunkRequirement(step, write);
    }

    @Override
    public String toString() {
        if (this.write) {
            return "ChunkRequirement{write@" + this.step + "}";
        } else {
            return "ChunkRequirement{read@" + this.step + "}";
        }
    }
}
