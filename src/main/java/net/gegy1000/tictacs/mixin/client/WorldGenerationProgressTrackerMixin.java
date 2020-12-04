package net.gegy1000.tictacs.mixin.client;

import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.server.WorldGenerationProgressLogger;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jetbrains.annotations.Nullable;

@Mixin(WorldGenerationProgressTracker.class)
public class WorldGenerationProgressTrackerMixin {
    @Shadow
    private ChunkPos spawnPos;

    @Shadow
    @Final
    private int size;

    @Shadow
    private boolean running;

    @Shadow
    @Final
    private WorldGenerationProgressLogger progressLogger;

    @Shadow
    @Final
    private int radius;

    private ChunkStatus[] array;

    private int offsetX;
    private int offsetZ;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(CallbackInfo ci) {
        this.array = new ChunkStatus[this.size * this.size];
    }

    @Inject(method = "start(Lnet/minecraft/util/math/ChunkPos;)V", at = @At("RETURN"))
    private void start(ChunkPos spawnPos, CallbackInfo ci) {
        this.array = new ChunkStatus[this.size * this.size];

        this.offsetX = this.radius - this.spawnPos.x;
        this.offsetZ = this.radius - this.spawnPos.z;
    }

    /**
     * @reason set into backing array
     * @author gegy1000
     */
    @Overwrite
    public void setChunkStatus(ChunkPos pos, @Nullable ChunkStatus status) {
        if (this.running) {
            this.progressLogger.setChunkStatus(pos, status);

            int idx = this.index(pos.x + this.offsetX, pos.z + this.offsetZ);
            if (idx != -1) {
                this.array[idx] = status;
            }
        }
    }

    /**
     * @reason get from backing array
     * @author gegy1000
     */
    @Overwrite
    @Nullable
    public ChunkStatus getChunkStatus(int x, int z) {
        int idx = this.index(x, z);
        if (idx == -1) {
            return null;
        }

        return this.array[idx];
    }

    private int index(int x, int z) {
        int size = this.size;
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return -1;
        }

        return x + z * size;
    }
}
