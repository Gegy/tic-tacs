package net.gegy1000.tictacs;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.network.ClientSidePacketRegistry;
import net.gegy1000.tictacs.client.TicTacsDebugLevelTracker;

@Environment(EnvType.CLIENT)
public class TicTacsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientSidePacketRegistry.INSTANCE.register(TicTacs.DEBUG_CHUNK_TICKETS, (packetContext, data) -> {
            long chunkPos = data.readLong();
            int toLevel = data.readInt();

            packetContext.getTaskQueue().execute(() -> {
                TicTacsDebugLevelTracker.INSTANCE.setLevel(chunkPos, toLevel);
            });
        });
    }
}
