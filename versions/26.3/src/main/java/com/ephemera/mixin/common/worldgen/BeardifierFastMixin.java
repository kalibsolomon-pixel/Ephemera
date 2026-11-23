package com.ephemera.mixin.common.worldgen;

import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Beardifier;
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

/**
 * 26.3 {@link Beardifier} optimization: flattens structure pieces and jigsaw junctions into
 * compact primitive {@code int[]} tables at construction time, eliminating two
 * {@code List.iterator()} allocations per block sample in {@code sampleValueUnchecked} and
 * short-circuiting out-of-range pieces/junctions immediately. Bit-exact float parity with 26.3.
 */
@Mixin(Beardifier.class)
abstract class BeardifierFastMixin {
	@Unique private static final int EP$MODE_BURY = 1;
	@Unique private static final int EP$MODE_BEARD_THIN = 2;
	@Unique private static final int EP$MODE_BEARD_BOX = 3;
	@Unique private static final int EP$MODE_ENCAPSULATE = 4;
	@Unique private static final int[] EP$EMPTY_INTS = new int[0];

	@Shadow @Final private static float[] BEARD_KERNEL;

	@Unique private int[] ephemera$pieceData = EP$EMPTY_INTS;
	@Unique private int[] ephemera$junctionData = EP$EMPTY_INTS;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$flatten(List<Beardifier.Rigid> pieces, List<JigsawJunction> junctions,
								  @Nullable BoundingBox affectedBox, CallbackInfo ci) {
		if (affectedBox == null) {
			return;
		}
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
	 * @reason Zero-allocation primitive-array traversal with early range rejects and bit-exact float math.
	 */
	@Overwrite
	private float sampleValueUnchecked(int x, int y, int z) {
		float sum = 0.0f;
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
						sum += ephemera$buryContribution((float) dx, (float) dyToGround / 2.0f, (float) dz);
					}
				}
				case EP$MODE_BEARD_THIN -> {
					sum += ephemera$beardContribution(dx, dyToGround, dz, dyToGround) * 0.8f;
				}
				case EP$MODE_BEARD_BOX -> {
					int maxY = pData[i + 4];
					int dyAdj = y < groundY ? (groundY - y) : (y > maxY ? (y - maxY) : 0);
					sum += ephemera$beardContribution(dx, dyAdj, dz, dyToGround) * 0.8f;
				}
				case EP$MODE_ENCAPSULATE -> {
					int minY = pData[i + 1];
					int maxY = pData[i + 4];
					int dyAdj = y < minY ? (minY - y) : (y > maxY ? (y - maxY) : 0);
					sum += ephemera$buryContribution((float) dx / 2.0f, (float) dyAdj / 2.0f, (float) dz / 2.0f) * 0.8f;
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
			sum += ephemera$beardContribution(dx, dy, dz, dy) * 0.4f;
		}
		return sum;
	}

	@Unique
	private static float ephemera$buryContribution(float x, float y, float z) {
		float lenSq = Mth.lengthSquared(x, y, z);
		return lenSq >= 36.0f ? 0.0f : 1.0f - Mth.sqrt(lenSq) / 6.0f;
	}

	@Unique
	private static float ephemera$beardContribution(int dx, int dy, int dz, int yToGround) {
		int kx = dx + 12;
		int ky = dy + 12;
		int kz = dz + 12;
		if (Integer.compareUnsigned(kx, 24) >= 0
			|| Integer.compareUnsigned(ky, 24) >= 0
			|| Integer.compareUnsigned(kz, 24) >= 0) {
			return 0.0f;
		}
		float yOff = (float) yToGround + 0.5f;
		float distSq = Mth.lengthSquared((float) dx, yOff, (float) dz);
		float factor = -yOff * (float) Mth.fastInvSqrt((double) (distSq / 2.0f)) / 2.0f;
		return factor * BEARD_KERNEL[kz * 576 + kx * 24 + ky];
	}
}
