package net.gegy1000.acttwo;

import net.gegy1000.acttwo.client.ActTwoDebugRenderer;
import net.gegy1000.acttwo.client.DebugRendererExt;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkPos;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.network.ClientSidePacketRegistry;

@Environment(EnvType.CLIENT)
public class ActTwoClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientSidePacketRegistry.INSTANCE.register(ActTwo.DEBUG_CHUNK_TICKETS, (packetContext, data) -> {
			ChunkPos chunkPos = new ChunkPos(data.readLong());
			int toLevel = data.readInt();
			long redTime = data.readLong();

			packetContext.getTaskQueue().execute(() -> ((DebugRendererExt) (MinecraftClient.getInstance().debugRenderer)).get().positions.put(chunkPos,
					new ActTwoDebugRenderer.PosEntry(chunkPos, Integer.toString(toLevel), redTime)));
		});
	}
}
