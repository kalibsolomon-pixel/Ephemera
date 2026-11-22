package com.ephemera.mixin.common.worldgen;

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

/**
 * Caches {@link NoiseChunk}'s {@code interpolators} list as a flat array so the per-block
 * {@link #updateForZ} (98,304 calls/chunk), {@link #updateForX}, {@link #updateForY}, and
 * {@link #swapSlices} loops never allocate {@code ArrayList$Itr} objects or dispatch through
 * {@code List}/{@code Iterator} interfaces.
 */
@Mixin(NoiseChunk.class)
abstract class NoiseChunkFastMixin {
	@Shadow @Final private List<NoiseChunk.NoiseInterpolator> interpolators;
	@Shadow private int cellStartBlockX;
	@Shadow private int cellStartBlockY;
	@Shadow private int cellStartBlockZ;
	@Shadow private int inCellX;
	@Shadow private int inCellY;
	@Shadow private int inCellZ;
	@Shadow private long interpolationCounter;

	@Unique private NoiseInterpolatorAccess[] ephemera$interps;

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
