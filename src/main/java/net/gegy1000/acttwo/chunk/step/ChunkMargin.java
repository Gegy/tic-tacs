package net.gegy1000.acttwo.chunk.step;

public class ChunkMargin {
    private static final ChunkMargin NONE = new ChunkMargin(0, false);

    public final int radius;
    public final boolean write;

    private ChunkMargin(int radius, boolean write) {
        this.radius = radius;
        this.write = write;
    }

    public static ChunkMargin none() {
        return NONE;
    }

    public static ChunkMargin read(int radius) {
        return new ChunkMargin(radius, false);
    }

    public static ChunkMargin write(int radius) {
        return new ChunkMargin(radius, true);
    }

    public boolean isEmpty() {
        return this.radius == 0;
    }

    @Override
    public String toString() {
        if (this.write) {
            return "ChunkMargin{write@" + this.radius + "}";
        } else {
            return "ChunkMargin{read@" + this.radius + "}";
        }
    }
}
