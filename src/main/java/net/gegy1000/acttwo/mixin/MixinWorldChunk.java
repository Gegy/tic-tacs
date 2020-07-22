package net.gegy1000.acttwo.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

@Mixin(WorldChunk.class)
public class MixinWorldChunk {

	@Shadow @Final private Map<Heightmap.Type, Heightmap> heightmaps;

	/**
	 * @reason The heightmap is null here for some reason, work around that by creating a new one.
	 * This should probably be refactored at some point, but it's fine for now.
	 *
	 * @author SuperCoder79
	 */
	@Overwrite
	public int sampleHeightmap(Heightmap.Type type, int x, int z) {
		Heightmap heightmap = this.heightmaps.computeIfAbsent(type, (typex) -> new Heightmap((WorldChunk)(Object) this, typex));
		return heightmap.get(x & 15, z & 15) - 1;
	}
}
