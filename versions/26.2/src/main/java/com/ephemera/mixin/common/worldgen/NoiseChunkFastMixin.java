package com.ephemera.mixin.common.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Caches {@link NoiseChunk}'s {@code interpolators} list as a flat array and caches the
 * {@code this::wrapNew} method reference so chunk noise fill never allocates {@code ArrayList$Itr}
 * objects or per-call {@code NoiseChunk$$Lambda} closures (~220 MB across worldgen).
 */
@Mixin(NoiseChunk.class)
abstract class NoiseChunkFastMixin {
	@Shadow @Final private List<NoiseChunk.NoiseInterpolator> interpolators;
	@Shadow @Final private Map<DensityFunction, DensityFunction> wrapped;
	@Shadow private int cellStartBlockX;
	@Shadow private int cellStartBlockY;
	@Shadow private int cellStartBlockZ;
	@Shadow private int inCellX;
	@Shadow private int inCellY;
	@Shadow private int inCellZ;
	@Shadow private long interpolationCounter;

	@Shadow
	private DensityFunction wrapNew(DensityFunction function) {
		throw new AssertionError();
	}

	@Unique private NoiseInterpolatorAccess[] ephemera$interps;
	@Unique private final Function<DensityFunction, DensityFunction> ephemera$wrapFn = this::wrapNew;

	/**
	 * @author Ephemera
	 * @reason Reuses a cached method-reference field instead of allocating a capturing lambda per wrap() call.
	 */
	@Overwrite
	protected DensityFunction wrap(DensityFunction function) {
		return this.wrapped.computeIfAbsent(function, this.ephemera$wrapFn);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$snapshotInterpolators(CallbackInfo ci) {
		ephemera$refreshInterps();
	}

	@Unique
	private NoiseInterpolatorAccess[] ephemera$interps() {
		NoiseInterpolatorAccess[] arr = this.ephemera$interps;
		if (arr == null || arr.length != this.interpolators.size()) {
			arr = ephemera$refreshInterps();
		}
		return arr;
	}

	@Unique
	private NoiseInterpolatorAccess[] ephemera$refreshInterps() {
		List<NoiseChunk.NoiseInterpolator> list = this.interpolators;
		int n = list.size();
		NoiseInterpolatorAccess[] arr = new NoiseInterpolatorAccess[n];
		for (int i = 0; i < n; i++) {
			arr[i] = (NoiseInterpolatorAccess) (Object) list.get(i);
		}
		this.ephemera$interps = arr;
		return arr;
	}

	/**
	 * @author Ephemera
	 * @reason Flat array iteration avoids per-call ArrayList iterator overhead during chunk noise fill.
	 */
	@Overwrite
	public void updateForY(int y, double delta) {
		this.inCellY = y - this.cellStartBlockY;
		NoiseInterpolatorAccess[] arr = ephemera$interps();
		for (int i = 0, n = arr.length; i < n; i++) {
			arr[i].ephemera$updateForY(delta);
		}
	}

	/**
	 * @author Ephemera
	 * @reason Flat array iteration avoids per-call ArrayList iterator overhead during chunk noise fill.
	 */
	@Overwrite
	public void updateForX(int x, double delta) {
		this.inCellX = x - this.cellStartBlockX;
		NoiseInterpolatorAccess[] arr = ephemera$interps();
		for (int i = 0, n = arr.length; i < n; i++) {
			arr[i].ephemera$updateForX(delta);
		}
	}

	/**
	 * @author Ephemera
	 * @reason Flat array iteration eliminates 98,304 ArrayList$Itr loops per chunk.
	 */
	@Overwrite
	public void updateForZ(int z, double delta) {
		this.inCellZ = z - this.cellStartBlockZ;
		this.interpolationCounter++;
		NoiseInterpolatorAccess[] arr = ephemera$interps();
		for (int i = 0, n = arr.length; i < n; i++) {
			arr[i].ephemera$updateForZ(delta);
		}
	}

	/**
	 * @author Ephemera
	 * @reason Flat array iteration avoids Consumer lambda dispatch per slice swap.
	 */
	@Overwrite
	public void swapSlices() {
		NoiseInterpolatorAccess[] arr = ephemera$interps();
		for (int i = 0, n = arr.length; i < n; i++) {
			arr[i].ephemera$swapSlices();
		}
	}
}
