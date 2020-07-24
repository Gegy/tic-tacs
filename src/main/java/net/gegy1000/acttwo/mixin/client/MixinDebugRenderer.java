package net.gegy1000.acttwo.mixin.client;

import net.gegy1000.acttwo.client.ActTwoDebugRenderer;
import net.gegy1000.acttwo.client.DebugRendererExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;

@Mixin(DebugRenderer.class)
public class MixinDebugRenderer implements DebugRendererExt {
	private ActTwoDebugRenderer actTwoDebugRenderer;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void handleConstructor(MinecraftClient client, CallbackInfo ci) {
		this.actTwoDebugRenderer = new ActTwoDebugRenderer();
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void handleRender(MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
		actTwoDebugRenderer.render(matrices, vertexConsumers, cameraX, cameraY, cameraZ);
	}

	@Override
	public ActTwoDebugRenderer get() {
		return actTwoDebugRenderer;
	}
}
