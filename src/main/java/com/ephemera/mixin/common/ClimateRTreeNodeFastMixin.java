package com.ephemera.mixin.common;

import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Flattens {@code Climate.RTree.Node.parameterSpace} (7 {@code Climate.Parameter} record objects)
 * into 14 inline primitive {@code long} fields for branch-lean, cache-local distance evaluation
 * with zero pointer chasing during multi-noise biome lookups.
 */
@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$Node")
abstract class ClimateRTreeNodeFastMixin {
	@Shadow protected Climate.Parameter[] parameterSpace;

	@Unique private long ep$min0, ep$max0;
	@Unique private long ep$min1, ep$max1;
	@Unique private long ep$min2, ep$max2;
	@Unique private long ep$min3, ep$max3;
	@Unique private long ep$min4, ep$max4;
	@Unique private long ep$min5, ep$max5;
	@Unique private long ep$min6, ep$max6;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$flattenBounds(List<Climate.Parameter> parameters, CallbackInfo ci) {
		Climate.Parameter[] ps = this.parameterSpace;
		if (ps.length == 7) {
			this.ep$min0 = ps[0].min(); this.ep$max0 = ps[0].max();
			this.ep$min1 = ps[1].min(); this.ep$max1 = ps[1].max();
			this.ep$min2 = ps[2].min(); this.ep$max2 = ps[2].max();
			this.ep$min3 = ps[3].min(); this.ep$max3 = ps[3].max();
			this.ep$min4 = ps[4].min(); this.ep$max4 = ps[4].max();
			this.ep$min5 = ps[5].min(); this.ep$max5 = ps[5].max();
			this.ep$min6 = ps[6].min(); this.ep$max6 = ps[6].max();
		}
	}

	/**
	 * @author Ephemera
	 * @reason Unrolled inline primitive-field 7D AABB squared distance (zero Parameter[] object chasing).
	 */
	@Overwrite
	public long distance(long[] values) {
		long d0 = ephemera$axisDist(values[0], this.ep$min0, this.ep$max0);
		long dist = d0 * d0;
		long d1 = ephemera$axisDist(values[1], this.ep$min1, this.ep$max1);
		dist += d1 * d1;
		long d2 = ephemera$axisDist(values[2], this.ep$min2, this.ep$max2);
		dist += d2 * d2;
		long d3 = ephemera$axisDist(values[3], this.ep$min3, this.ep$max3);
		dist += d3 * d3;
		long d4 = ephemera$axisDist(values[4], this.ep$min4, this.ep$max4);
		dist += d4 * d4;
		long d5 = ephemera$axisDist(values[5], this.ep$min5, this.ep$max5);
		dist += d5 * d5;
		long d6 = ephemera$axisDist(values[6], this.ep$min6, this.ep$max6);
		return dist + d6 * d6;
	}

	@Unique
	private static long ephemera$axisDist(long v, long min, long max) {
		long over = v - max;
		if (over > 0L) {
			return over;
		}
		long under = min - v;
		return under > 0L ? under : 0L;
	}
}
