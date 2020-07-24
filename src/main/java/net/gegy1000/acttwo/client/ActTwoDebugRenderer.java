package net.gegy1000.acttwo.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class ActTwoDebugRenderer implements DebugRenderer.Renderer {
	public final Map<ChunkPos, PosEntry> positions = new ConcurrentHashMap<>();
	@Override
	public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, double cameraX, double cameraY, double cameraZ) {
		RenderSystem.pushMatrix();
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ZERO);
		RenderSystem.color4f(0.0F, 1.0F, 0.0F, 0.75F);
		RenderSystem.disableTexture();
		long systime = System.currentTimeMillis();
		for (Map.Entry<ChunkPos, PosEntry> entry : positions.entrySet()) {
			BlockPos blockPos = entry.getKey().getCenterBlockPos().up(128);
			double d = (double)blockPos.getX() + 8.5D;
			double e = (double)blockPos.getY() + 1.2D;
			double f = (double)blockPos.getZ() + 8.5D;
			int color = systime <= entry.getValue().redTime ? 0xff0000 : -1;

			DebugRenderer.drawString(entry.getValue().level, d, e, f, color, 0.25F, true, 0.0F, true);
		}

		RenderSystem.enableTexture();
		RenderSystem.disableBlend();
		RenderSystem.popMatrix();
	}

	public static class PosEntry {
		public final ChunkPos pos;
		public final String level;
		public final long redTime;

		public PosEntry(ChunkPos pos, String level, long redTime) {
			this.pos = pos;
			this.level = level;
			this.redTime = redTime;
		}
	}
}
