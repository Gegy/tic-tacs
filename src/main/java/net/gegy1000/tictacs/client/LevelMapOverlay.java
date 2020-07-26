package net.gegy1000.tictacs.client;

import net.gegy1000.tictacs.TicTacs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;

public final class LevelMapOverlay extends DrawableHelper implements AutoCloseable {
    private static final MinecraftClient CLIENT = MinecraftClient.getInstance();
    private static final Identifier TEXTURE_ID = new Identifier(TicTacs.ID, "level_map");

    private NativeImageBackedTexture texture;
    private int textureWidth;
    private int textureHeight;

    private long lastTextureUpdate;

    public void render(MatrixStack transform) {
        ClientWorld world = CLIENT.world;
        if (world == null) {
            return;
        }

        long time = world.getTime();
        if (time - this.lastTextureUpdate > 20) {
            NativeImage image = LevelMapRenderer.render(TicTacsDebugLevelTracker.INSTANCE);
            this.updateTexture(image);

            this.lastTextureUpdate = time;
        }

        CLIENT.getTextureManager().bindTexture(TEXTURE_ID);
        DrawableHelper.drawTexture(transform, 0, 0, 0.0F, 0.0F, this.textureWidth, this.textureHeight, this.textureWidth, this.textureHeight);
    }

    private void updateTexture(NativeImage image) {
        this.releaseTexture();

        this.texture = new NativeImageBackedTexture(image);
        this.textureWidth = image.getWidth();
        this.textureHeight = image.getHeight();

        CLIENT.getTextureManager().registerTexture(TEXTURE_ID, this.texture);
    }

    @Override
    public void close() {
        this.releaseTexture();
        CLIENT.getTextureManager().destroyTexture(TEXTURE_ID);
    }

    private void releaseTexture() {
        if (this.texture != null) {
            this.texture.close();
        }
    }
}
