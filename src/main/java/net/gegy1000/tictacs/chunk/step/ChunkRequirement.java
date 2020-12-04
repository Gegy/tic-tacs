package net.gegy1000.tictacs.chunk.step;

public final class ChunkRequirement {
    public final ChunkStep step;
    public final boolean read;
    public final boolean write;

    ChunkRequirement(ChunkStep step, boolean read, boolean write) {
        this.step = step;
        this.read = read;
        this.write = write;
    }

    public static ChunkRequirement write(ChunkStep step) {
        return new ChunkRequirement(step, true, true);
    }

    public static ChunkRequirement read(ChunkStep step) {
        return new ChunkRequirement(step, true, false);
    }

    public static ChunkRequirement require(ChunkStep step) {
        return new ChunkRequirement(step, false, false);
    }

    public static ChunkRequirement merge(ChunkRequirement a, ChunkRequirement b) {
        if (a == null) return b;
        if (b == null) return a;

        ChunkStep step = ChunkStep.max(a.step, b.step);
        boolean read = a.read || b.read;
        boolean write = a.write || b.write;

        return new ChunkRequirement(step, read, write);
    }

    @Override
    public String toString() {
        if (this.write) {
            return "ChunkRequirement{write@" + this.step + "}";
        } else if (this.read) {
            return "ChunkRequirement{read@" + this.step + "}";
        } else {
            return "ChunkRequirement@" + this.step;
        }
    }
}
