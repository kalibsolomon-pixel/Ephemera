package com.ephemera.mixin.common;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.jspecify.annotations.Nullable;
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
 * Flattens {@link Beardifier}'s structure piece and jigsaw junction lists into compact primitive
 * {@code int[]} tables at construction time, eliminating two {@code List.iterator()} allocations
 * per block sample and short-circuiting out-of-range pieces/junctions immediately.
 */
@Mixin(Beardifier.class)
abstract class BeardifierFastMixin {
	@Unique private static final int EP$MODE_BURY = 1;
	@Unique private static final int EP$MODE_BEARD_THIN = 2;
	@Unique private static final int EP$MODE_BEARD_BOX = 3;
	@Unique private static final int EP$MODE_ENCAPSULATE = 4;

	@Unique private static final int[] EP$EMPTY_INTS = new int[0];

	@Shadow @Final private static float[] BEARD_KERNEL;

	@Unique private boolean ephemera$hasBox;
	@Unique private int ephemera$boxMinX, ephemera$boxMinY, ephemera$boxMinZ;
	@Unique private int ephemera$boxMaxX, ephemera$boxMaxY, ephemera$boxMaxZ;
	@Unique private int[] ephemera$pieceData = EP$EMPTY_INTS;
	@Unique private int[] ephemera$junctionData = EP$EMPTY_INTS;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$flatten(List<Beardifier.Rigid> pieces, List<JigsawJunction> junctions,
								  @Nullable BoundingBox affectedBox, CallbackInfo ci) {
		if (affectedBox == null) {
			this.ephemera$hasBox = false;
			return;
		}
		this.ephemera$hasBox = true;
		this.ephemera$boxMinX = affectedBox.minX();
		this.ephemera$boxMinY = affectedBox.minY();
		this.ephemera$boxMinZ = affectedBox.minZ();
		this.ephemera$boxMaxX = affectedBox.maxX();
		this.ephemera$boxMaxY = affectedBox.maxY();
		this.ephemera$boxMaxZ = affectedBox.maxZ();

		int activePieces = 0;
		for (int i = 0, n = pieces.size(); i < n; i++) {
			if (pieces.get(i).terrainAdjustment() != TerrainAdjustment.NONE) {
				activePieces++;
			}
		}
		if (activePieces > 0) {
			int[] pData = new int[activePieces * 8];
			int out = 0;
			for (int i = 0, n = pieces.size(); i < n; i++) {
				Beardifier.Rigid rigid = pieces.get(i);
				int mode = switch (rigid.terrainAdjustment()) {
					case NONE -> 0;
					case BURY -> EP$MODE_BURY;
					case BEARD_THIN -> EP$MODE_BEARD_THIN;
					case BEARD_BOX -> EP$MODE_BEARD_BOX;
					case ENCAPSULATE -> EP$MODE_ENCAPSULATE;
				};
				if (mode == 0) {
					continue;
				}
				BoundingBox box = rigid.box();
				pData[out] = box.minX();
				pData[out + 1] = box.minY();
				pData[out + 2] = box.minZ();
				pData[out + 3] = box.maxX();
				pData[out + 4] = box.maxY();
				pData[out + 5] = box.maxZ();
				pData[out + 6] = box.minY() + rigid.groundLevelDelta();
				pData[out + 7] = mode;
				out += 8;
			}
			this.ephemera$pieceData = pData;
		}

		int jCount = junctions.size();
		if (jCount > 0) {
			int[] jData = new int[jCount * 3];
			for (int i = 0, out = 0; i < jCount; i++, out += 3) {
				JigsawJunction j = junctions.get(i);
				jData[out] = j.getSourceX();
				jData[out + 1] = j.getSourceGroundY();
				jData[out + 2] = j.getSourceZ();
			}
			this.ephemera$junctionData = jData;
		}
	}

	/**
	 * @author Ephemera
	 * @reason Zero-allocation primitive-array traversal with early range rejects.
	 */
	@Overwrite
	public double compute(DensityFunction.FunctionContext context) {
		if (!this.ephemera$hasBox) {
			return 0.0;
		}
		int x = context.blockX();
		if (x < this.ephemera$boxMinX || x > this.ephemera$boxMaxX) {
			return 0.0;
		}
		int z = context.blockZ();
		if (z < this.ephemera$boxMinZ || z > this.ephemera$boxMaxZ) {
			return 0.0;
		}
		int y = context.blockY();
		if (y < this.ephemera$boxMinY || y > this.ephemera$boxMaxY) {
			return 0.0;
		}

		double sum = 0.0;
		int[] pData = this.ephemera$pieceData;
		for (int i = 0, n = pData.length; i < n; i += 8) {
			int minX = pData[i];
			int maxX = pData[i + 3];
			int dx = x < minX ? (minX - x) : (x > maxX ? (x - maxX) : 0);
			if (dx >= 12) {
				continue;
			}
			int minZ = pData[i + 2];
			int maxZ = pData[i + 5];
			int dz = z < minZ ? (minZ - z) : (z > maxZ ? (z - maxZ) : 0);
			if (dz >= 12) {
				continue;
			}
			int groundY = pData[i + 6];
			int dyToGround = y - groundY;
			int mode = pData[i + 7];
			switch (mode) {
				case EP$MODE_BURY -> {
					if (dx < 6 && dz < 6) {
						sum += ephemera$buryContribution((double) dx, (double) dyToGround / 2.0, (double) dz);
					}
				}
				case EP$MODE_BEARD_THIN -> {
					sum += ephemera$beardContribution(dx, dyToGround, dz, dyToGround) * 0.8;
				}
				case EP$MODE_BEARD_BOX -> {
					int maxY = pData[i + 4];
					int dyAdj = y < groundY ? (groundY - y) : (y > maxY ? (y - maxY) : 0);
					sum += ephemera$beardContribution(dx, dyAdj, dz, dyToGround) * 0.8;
				}
				case EP$MODE_ENCAPSULATE -> {
					int minY = pData[i + 1];
					int maxY = pData[i + 4];
					int dyAdj = y < minY ? (minY - y) : (y > maxY ? (y - maxY) : 0);
					sum += ephemera$buryContribution((double) dx / 2.0, (double) dyAdj / 2.0, (double) dz / 2.0) * 0.8;
				}
			}
		}

		int[] jData = this.ephemera$junctionData;
		for (int i = 0, n = jData.length; i < n; i += 3) {
			int dx = x - jData[i];
			if (Integer.compareUnsigned(dx + 12, 24) >= 0) {
				continue;
			}
			int dz = z - jData[i + 2];
			if (Integer.compareUnsigned(dz + 12, 24) >= 0) {
				continue;
			}
			int dy = y - jData[i + 1];
			sum += ephemera$beardContribution(dx, dy, dz, dy) * 0.4;
		}
		return sum;
	}

	@Unique
	private static double ephemera$buryContribution(double x, double y, double z) {
		double len = Mth.length(x, y, z);
		return Mth.clampedMap(len, 0.0, 6.0, 1.0, 0.0);
	}

	@Unique
	private static double ephemera$beardContribution(int dx, int dy, int dz, int yToGround) {
		int kx = dx + 12;
		int ky = dy + 12;
		int kz = dz + 12;
		if (Integer.compareUnsigned(kx, 24) >= 0
			|| Integer.compareUnsigned(ky, 24) >= 0
			|| Integer.compareUnsigned(kz, 24) >= 0) {
			return 0.0;
		}
		double yOff = (double) yToGround + 0.5;
		double distSq = Mth.lengthSquared((double) dx, yOff, (double) dz);
		double factor = -yOff * Mth.fastInvSqrt(distSq / 2.0) / 2.0;
		return factor * (double) BEARD_KERNEL[kz * 576 + kx * 24 + ky];
	}
}
