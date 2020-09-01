package net.gegy1000.tictacs.mixin.client.debug;

import net.gegy1000.tictacs.client.TicTacsDebugRenderer;
import net.gegy1000.tictacs.client.DebugRendererExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;

@Mixin(DebugRenderer.class)
public class DebugRendererMixin implements DebugRendererExt {
	private TicTacsDebugRenderer ticTacsDebugRenderer;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void handleConstructor(MinecraftClient client, CallbackInfo ci) {
		this.ticTacsDebugRenderer = new TicTacsDebugRenderer();
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void handleRender(MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        this.ticTacsDebugRenderer.render(matrices, vertexConsumers, cameraX, cameraY, cameraZ);
	}

	@Override
	public TicTacsDebugRenderer get() {
		return this.ticTacsDebugRenderer;
	}
}
