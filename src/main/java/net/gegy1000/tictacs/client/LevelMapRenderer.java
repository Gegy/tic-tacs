package net.gegy1000.tictacs.client;

import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkController;
import net.gegy1000.tictacs.chunk.ChunkLevelTracker;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.Rect2i;
import net.minecraft.server.world.ChunkTicket;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.collection.SortedArraySet;
import net.minecraft.util.math.ChunkPos;

import java.util.Collection;

public final class LevelMapRenderer {
    private static final int MAX_LEVEL = ChunkLevelTracker.MAX_LEVEL + 1;

    public static NativeImage render(ChunkController controller) {
        ChunkTicketManager ticketManager = controller.getTicketManager();

        ChunkAccess map = controller.getMap().primary();
        Collection<ChunkEntry> entries = map.getEntries();

        Rect2i bounds = computeBounds(entries);

        int minX = bounds.getX();
        int minY = bounds.getY();

        int width = bounds.getWidth();
        int height = bounds.getHeight();

        NativeImage image = new NativeImage(width, height, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long pos = ChunkPos.toLong(x + minX, y + minY);
                ChunkEntry entry = map.getEntry(pos);

                SortedArraySet<ChunkTicket<?>> tickets = ticketManager.ticketsByPosition.get(pos);
                if (tickets != null && !tickets.isEmpty()) {
                    boolean player = false;
                    boolean light = false;
                    for (ChunkTicket<?> ticket : tickets) {
                        ChunkTicketType<?> type = ticket.getType();
                        if (type == ChunkTicketType.PLAYER) player = true;
                        if (type == ChunkTicketType.LIGHT) light = true;
                    }

                    int color = 0xFF0000FF;
                    if (player) color |= 0xFF0000; // blue
                    if (light) color |= 0xFF00; // green

                    image.setPixelColor(x, y, color);

                    continue;
                }

                if (entry != null && ChunkLevelTracker.isLoaded(entry.getLevel())) {
                    int level = entry.getLevel();
                    int levelRange = MAX_LEVEL - ChunkEntry.FULL_LEVEL;

                    int renderLevel = Math.max(level, ChunkEntry.FULL_LEVEL) - ChunkEntry.FULL_LEVEL;

                    int brightness = (levelRange - renderLevel) * 255 / levelRange;
                    int color = (0xFF << 24) | (brightness << 16) | (brightness << 8) | brightness;

                    image.setPixelColor(x, y, color);
                } else {
                    image.setPixelColor(x, y, 0xFFFF0000);
                }
            }
        }

        return image;
    }

    private static Rect2i computeBounds(Collection<ChunkEntry> entries) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (ChunkEntry entry : entries) {
            ChunkPos pos = entry.getPos();
            int x = pos.x;
            int z = pos.z;

            if (x < minX) minX = x;
            if (x > maxX) maxX = x;

            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        return new Rect2i(minX, minZ, maxX - minX, maxZ - minZ);
    }
}