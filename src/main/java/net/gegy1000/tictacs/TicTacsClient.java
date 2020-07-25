package net.gegy1000.tictacs;

import net.gegy1000.tictacs.client.TicTacsDebugRenderer;
import net.gegy1000.tictacs.client.DebugRendererExt;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkPos;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.network.ClientSidePacketRegistry;

@Environment(EnvType.CLIENT)
public class TicTacsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientSidePacketRegistry.INSTANCE.register(TicTacs.DEBUG_CHUNK_TICKETS, (packetContext, data) -> {
			ChunkPos chunkPos = new ChunkPos(data.readLong());
			int toLevel = data.readInt();
			long redTime = data.readLong();

			packetContext.getTaskQueue().execute(() -> ((DebugRendererExt) (MinecraftClient.getInstance().debugRenderer)).get().positions.put(chunkPos,
					new TicTacsDebugRenderer.PosEntry(chunkPos, Integer.toString(toLevel), redTime)));
		});
	}
}
