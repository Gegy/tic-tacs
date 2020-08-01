package net.gegy1000.tictacs.chunk;

public final class ChunkNotLoadedException extends RuntimeException {
    public static final ChunkNotLoadedException INSTANCE = new ChunkNotLoadedException();

    private ChunkNotLoadedException() {
    }
}
