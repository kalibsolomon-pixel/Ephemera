package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RotatingGridMathTest {
	@Test
	void fastModAndWorldCoordMatchFloorModAcrossNegativeAndPositiveCoords() {
		int[] radii = {2, 8, 16, 20, 32};
		int[] centers = {-10000, -100, -1, 0, 1, 7, 41, 10000};
		for (int radius : radii) {
			int gridXZ = radius * 2 + 1;
			for (int cx : centers) {
				int minX = cx - radius;
				int baseMod = Math.floorMod(minX, gridXZ);
				for (int x = minX; x < minX + gridXZ; x++) {
					int slot = RotatingGridMath.fastModInWindow(x, minX, baseMod, gridXZ);
					assertEquals(Math.floorMod(x, gridXZ), slot);
					assertEquals(x, RotatingGridMath.worldCoordForSlot(slot, minX, baseMod, gridXZ));
				}
			}
		}
	}

	@Test
	void incrementalRepositionMatchesFullScanExactly() {
		int radius = 12;
		int gridXZ = radius * 2 + 1;
		int[] refX = new int[gridXZ * gridXZ];
		int[] refZ = new int[gridXZ * gridXZ];
		int[] incX = new int[gridXZ * gridXZ];
		int[] incZ = new int[gridXZ * gridXZ];

		boolean initialized = false;
		int curMinX = 0, curMinZ = 0, curBaseX = 0, curBaseZ = 0;

		Random rng = new Random(0x51C3L);
		int cx = 0, cz = 0;
		for (int step = 0; step < 300; step++) {
			int mode = rng.nextInt(10);
			if (mode < 6) {
				cx += rng.nextInt(3) - 1;
				cz += rng.nextInt(3) - 1;
			} else if (mode < 9) {
				cx += rng.nextInt(11) - 5;
				cz += rng.nextInt(11) - 5;
			} else {
				cx = rng.nextInt(2000) - 1000;
				cz = rng.nextInt(2000) - 1000;
			}

			int newMinX = cx - radius;
			int newMinZ = cz - radius;
			int newBaseX = Math.floorMod(newMinX, gridXZ);
			int newBaseZ = Math.floorMod(newMinZ, gridXZ);

			// Reference full scan
			for (int gx = 0; gx < gridXZ; gx++) {
				int wx = newMinX + Math.floorMod(gx - newMinX, gridXZ);
				for (int gz = 0; gz < gridXZ; gz++) {
					int wz = newMinZ + Math.floorMod(gz - newMinZ, gridXZ);
					int idx = gz * gridXZ + gx;
					refX[idx] = wx;
					refZ[idx] = wz;
				}
			}

			// Incremental update
			int shiftX = newMinX - curMinX;
			int shiftZ = newMinZ - curMinZ;
			if (!initialized || Math.abs(shiftX) >= gridXZ || Math.abs(shiftZ) >= gridXZ) {
				for (int gx = 0; gx < gridXZ; gx++) {
					int wx = RotatingGridMath.worldCoordForSlot(gx, newMinX, newBaseX, gridXZ);
					for (int gz = 0; gz < gridXZ; gz++) {
						int wz = RotatingGridMath.worldCoordForSlot(gz, newMinZ, newBaseZ, gridXZ);
						int idx = gz * gridXZ + gx;
						incX[idx] = wx;
						incZ[idx] = wz;
					}
				}
				initialized = true;
			} else {
				if (shiftX != 0) {
					int countX = Math.abs(shiftX);
					int gx = RotatingGridMath.dirtyStartSlot(curBaseX, newBaseX, shiftX);
					for (int k = 0; k < countX; k++) {
						int wx = RotatingGridMath.worldCoordForSlot(gx, newMinX, newBaseX, gridXZ);
						for (int gz = 0; gz < gridXZ; gz++) {
							int wz = RotatingGridMath.worldCoordForSlot(gz, newMinZ, newBaseZ, gridXZ);
							int idx = gz * gridXZ + gx;
							incX[idx] = wx;
							incZ[idx] = wz;
						}
						if (++gx == gridXZ) gx = 0;
					}
				}
				if (shiftZ != 0) {
					int countZ = Math.abs(shiftZ);
					int gz = RotatingGridMath.dirtyStartSlot(curBaseZ, newBaseZ, shiftZ);
					for (int k = 0; k < countZ; k++) {
						int wz = RotatingGridMath.worldCoordForSlot(gz, newMinZ, newBaseZ, gridXZ);
						for (int gx = 0; gx < gridXZ; gx++) {
							if (shiftX != 0 && RotatingGridMath.isSlotDirty(gx, curBaseX, newBaseX, shiftX, gridXZ)) {
								continue;
							}
							int wx = RotatingGridMath.worldCoordForSlot(gx, newMinX, newBaseX, gridXZ);
							int idx = gz * gridXZ + gx;
							incX[idx] = wx;
							incZ[idx] = wz;
						}
						if (++gz == gridXZ) gz = 0;
					}
				}
			}
			curMinX = newMinX;
			curMinZ = newMinZ;
			curBaseX = newBaseX;
			curBaseZ = newBaseZ;

			for (int i = 0; i < refX.length; i++) {
				assertEquals(refX[i], incX[i], "step=" + step + " idx=" + i);
				assertEquals(refZ[i], incZ[i], "step=" + step + " idx=" + i);
			}
		}
	}
}
