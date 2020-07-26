package net.gegy1000.tictacs.client;

import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.Rect2i;
import net.minecraft.util.math.ChunkPos;

public final class LevelMapRenderer {
    public static NativeImage render(TicTacsDebugLevelTracker tracker) {
        Rect2i bounds = tracker.computeBounds();

        int minX = bounds.getX();
        int minY = bounds.getY();

        int width = bounds.getWidth();
        int height = bounds.getHeight();

        NativeImage image = new NativeImage(width, height, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int level = tracker.getLevel(ChunkPos.toLong(x + minX, y + minY));

                int brightness = level * 255 / ChunkLevelTracker.MAX_LEVEL;
                int color = (0xFF << 24) | (brightness << 16) | (brightness << 8) | brightness;

                image.setPixelColor(x, y, color);
            }
        }

        return image;
    }
}
