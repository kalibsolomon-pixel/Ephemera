package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import java.util.Arrays;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Replaces the thrashing 100-entry {@code Long2IntLinkedOpenHashMap} /
 * {@code Long2FloatLinkedOpenHashMap} LRU caches inside {@code BlockModelLighter$Cache}
 * with direct-mapped 1024-entry L1-resident spatial caches ({@code 16x16x4} local neighborhood,
 * O(1) generation-bumped clear, zero eviction/hashing overhead, bit-exact {@code BlockPos.asLong()}
 * verification). Uses {@code @Overwrite} so primitive {@code int}/{@code float} returns avoid
 * {@code CallbackInfoReturnable} and {@code Integer}/{@code Float} boxing (~5.8 GB / 60s saved).
 */
@Mixin(targets = "net.minecraft.client.renderer.block.BlockModelLighter$Cache")
abstract class BlockModelLighterCacheFastMixin {
	@Unique private static final int EPHEMERA$SIZE = 1024;

	@Shadow private boolean enabled;

	@Unique private int ephemera$generation = 1;
	@Unique private final long[] ephemera$lightKeys = new long[EPHEMERA$SIZE];
	@Unique private final int[] ephemera$lightGens = new int[EPHEMERA$SIZE];
	@Unique private final int[] ephemera$lightVals = new int[EPHEMERA$SIZE];

	@Unique private final long[] ephemera$shadeKeys = new long[EPHEMERA$SIZE];
	@Unique private final int[] ephemera$shadeGens = new int[EPHEMERA$SIZE];
	@Unique private final float[] ephemera$shadeVals = new float[EPHEMERA$SIZE];

	@Unique
	private static int ephemera$slot(BlockPos pos) {
		return (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 3) << 8);
	}

	/**
	 * @author Ephemera
	 * @reason O(1) generation-bumped cache invalidation avoids clearing two fastutil linked hash maps per section.
	 */
	@Overwrite
	public void disable() {
		this.enabled = false;
		int nextGen = this.ephemera$generation + 1;
		if (nextGen == 0) {
			Arrays.fill(this.ephemera$lightGens, 0);
			Arrays.fill(this.ephemera$shadeGens, 0);
			nextGen = 1;
		}
		this.ephemera$generation = nextGen;
	}

	/**
	 * @author Ephemera
	 * @reason Direct-mapped 1024-entry spatial cache avoids 100-entry LinkedOpenHashMap thrashing and primitive boxing.
	 */
	@Overwrite
	public int getLightCoords(BlockState state, BlockAndTintGetter level, BlockPos pos) {
		if (state.emissiveRendering()) {
			return LightCoordsUtil.FULL_BRIGHT;
		}
		int packed;
		if (this.enabled && EphemeraCommon.config().terrainBitmaskMeshing) {
			long key = pos.asLong();
			int slot = ephemera$slot(pos);
			int gen = this.ephemera$generation;
			if (this.ephemera$lightGens[slot] == gen && this.ephemera$lightKeys[slot] == key) {
				packed = this.ephemera$lightVals[slot];
			} else {
				int sky = level.getBrightness(LightLayer.SKY, pos);
				int block = level.getBrightness(LightLayer.BLOCK, pos);
				packed = LightCoordsUtil.pack(block, sky);
				this.ephemera$lightKeys[slot] = key;
				this.ephemera$lightGens[slot] = gen;
				this.ephemera$lightVals[slot] = packed;
			}
		} else {
			int sky = level.getBrightness(LightLayer.SKY, pos);
			int block = level.getBrightness(LightLayer.BLOCK, pos);
			packed = LightCoordsUtil.pack(block, sky);
		}
		int blockLight = LightCoordsUtil.block(packed);
		int emission = state.getLightEmission();
		if (blockLight < emission) {
			return LightCoordsUtil.withBlock(packed, emission);
		}
		return packed;
	}

	/**
	 * @author Ephemera
	 * @reason Direct-mapped 1024-entry spatial cache avoids 100-entry LinkedOpenHashMap thrashing and Float boxing.
	 */
	@Overwrite
	public float getShadeBrightness(BlockState state, BlockAndTintGetter level, BlockPos pos) {
		if (!this.enabled || !EphemeraCommon.config().terrainBitmaskMeshing) {
			return state.getShadeBrightness(level, pos);
		}
		long key = pos.asLong();
		int slot = ephemera$slot(pos);
		int gen = this.ephemera$generation;
		if (this.ephemera$shadeGens[slot] == gen && this.ephemera$shadeKeys[slot] == key) {
			return this.ephemera$shadeVals[slot];
		}
		float shade = state.getShadeBrightness(level, pos);
		this.ephemera$shadeKeys[slot] = key;
		this.ephemera$shadeGens[slot] = gen;
		this.ephemera$shadeVals[slot] = shade;
		return shade;
	}
}
