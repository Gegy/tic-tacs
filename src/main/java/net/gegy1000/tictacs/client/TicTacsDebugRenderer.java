package net.gegy1000.tictacs.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ChunkPos;

@Environment(EnvType.CLIENT)
public class TicTacsDebugRenderer implements DebugRenderer.Renderer {
	@Override
	public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, double cameraX, double cameraY, double cameraZ) {
		RenderSystem.pushMatrix();
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ZERO);
		RenderSystem.color4f(0.0F, 1.0F, 0.0F, 0.75F);
		RenderSystem.disableTexture();

		long systime = System.currentTimeMillis();

		TicTacsDebugLevelTracker tracker = TicTacsDebugLevelTracker.INSTANCE;

		LongIterator iterator = tracker.chunks().iterator();
		while (iterator.hasNext()) {
			long pos = iterator.nextLong();
			int chunkX = ChunkPos.getPackedX(pos);
			int chunkZ = ChunkPos.getPackedZ(pos);

			int level = tracker.getLevel(pos);
			long redTime = tracker.getRedTime(pos);

			double x = (chunkX << 4) + 8.5;
			double y = 128 + 1.2;
			double z = (chunkZ << 4) + 8.5;

			int color = systime <= redTime ? 0xff0000 : -1;

			DebugRenderer.drawString(String.valueOf(level), x, y, z, color, 0.25F, true, 0.0F, true);
		}

		RenderSystem.enableTexture();
		RenderSystem.disableBlend();
		RenderSystem.popMatrix();
	}
}
