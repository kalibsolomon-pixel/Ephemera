package com.ephemera.mixin.common.worldgen;

import net.minecraft.util.BoundedFloatFunction;
import net.minecraft.util.CubicSpline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Eliminates Java {@code Stream} / {@code ReferencePipeline} / {@code Spliterator} allocations
 * inside {@link CubicSpline.Multipoint#mapCoordinates} on MC 26.3+.
 */
@Mixin(CubicSpline.Multipoint.class)
abstract class CubicSplineFastMixin<I extends BoundedFloatFunction<?>> {
	@Shadow @Final private I coordinate;
	@Shadow @Final private float[] locations;
	@Shadow @Final private List<CubicSpline<I>> values;
	@Shadow @Final private float[] derivatives;

	/**
	 * @author Ephemera
	 * @reason Direct indexed loop avoids Stream pipeline + Spliterator + lambda allocations per spline node.
	 */
	@Overwrite
	public <R extends BoundedFloatFunction<?>> CubicSpline<R> mapCoordinates(Function<I, R> visitor) {
		R mappedCoord = visitor.apply(this.coordinate);
		List<CubicSpline<I>> src = this.values;
		int n = src.size();
		ArrayList<CubicSpline<R>> mapped = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			mapped.add(src.get(i).mapCoordinates(visitor));
		}
		return new CubicSpline.Multipoint<>(mappedCoord, this.locations, Collections.unmodifiableList(mapped), this.derivatives);
	}
}
