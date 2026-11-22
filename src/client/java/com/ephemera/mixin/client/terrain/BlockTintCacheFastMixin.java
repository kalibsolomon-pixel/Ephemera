package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.terrain.BiomePrefixBlender;
import com.ephemera.render.terrain.BlockTintCachePrefixHook;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Arrays;
import java.util.function.ToIntFunction;

/**
 * Wires {@link BiomePrefixBlender} into {@link BlockTintCache#getColor(BlockPos)}.
 *
 * <p>On the first cache miss in a chunk's 16x16 Y-slice, samples the {@code (16 + 2R)^2}
 * apron once, short-circuits uniform single-biome slices with {@link Arrays#fill}, and
 * otherwise computes all 256 blended ARGB values in O(1) per block (16x–64x fewer biome
 * lookups than vanilla's per-block {@code (2R + 1)^2} sweep).
 */
@Mixin(BlockTintCache.class)
abstract class BlockTintCacheFastMixin implements BlockTintCachePrefixHook {
	@Unique
	private static final ThreadLocal<EphemeraTintScratch> EPHEMERA$SCRATCH =
		ThreadLocal.withInitial(EphemeraTintScratch::new);

	@Unique private ClientLevel ephemera$level;
	@Unique private ColorResolver ephemera$resolver;

	@Override
	public void ephemera$bind(ClientLevel level, ColorResolver resolver) {
		this.ephemera$level = level;
		this.ephemera$resolver = resolver;
	}

	@WrapOperation(
		method = "getColor",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/function/ToIntFunction;applyAsInt(Ljava/lang/Object;)I"
		)
	)
	private int ephemera$blendWholeLayer(
		ToIntFunction<BlockPos> source,
		Object posObj,
		Operation<Integer> original,
		@Local int[] layer
	) {
		ClientLevel level = this.ephemera$level;
		ColorResolver resolver = this.ephemera$resolver;
		if (level == null || resolver == null
			|| !EphemeraCommon.config().terrainPrefixSumBiomeBlend
			|| layer == null || layer.length != 256
			|| !(posObj instanceof BlockPos pos)) {
			return original.call(source, posObj);
		}
		int radius = Minecraft.getInstance().options.biomeBlendRadius().get();
		if (radius <= 0 || radius > 15) {
			return original.call(source, posObj);
		}

		int minX = (pos.getX() & ~15) - radius;
		int y = pos.getY();
		int minZ = (pos.getZ() & ~15) - radius;
		int side = 16 + (radius << 1);
		int total = side * side;

		EphemeraTintScratch scratch = EPHEMERA$SCRATCH.get();
		int[] raw = scratch.raw(total);
		BlockPos.MutableBlockPos cursor = scratch.cursor;

		int firstColor = 0;
		boolean uniform = true;
		int idx = 0;
		for (int dz = 0; dz < side; dz++) {
			int wz = minZ + dz;
			for (int dx = 0; dx < side; dx++) {
				int wx = minX + dx;
				cursor.set(wx, y, wz);
				int color = resolver.getColor(level.getBiome(cursor).value(), wx, wz);
				raw[idx] = color;
				if (idx == 0) {
					firstColor = color;
				} else if (color != firstColor) {
					uniform = false;
				}
				idx++;
			}
		}

		if (uniform) {
			int packed = 0xFF000000 | (firstColor & 0x00FFFFFF);
			Arrays.fill(layer, packed);
			return packed;
		}

		int[] out = scratch.out;
		scratch.blender.blendOpaqueArgb(raw, side, side, radius, out);
		System.arraycopy(out, 0, layer, 0, 256);
		return out[((pos.getZ() & 15) << 4) | (pos.getX() & 15)];
	}

	@Unique
	private static final class EphemeraTintScratch {
		final BiomePrefixBlender blender = new BiomePrefixBlender();
		final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		final int[] out = new int[256];
		private int[] raw = new int[0];

		int[] raw(int needed) {
			if (this.raw.length < needed) {
				this.raw = new int[needed];
			}
			return this.raw;
		}
	}
}
