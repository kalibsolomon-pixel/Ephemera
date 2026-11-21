package com.ephemera.core.terrain;

/**
 * Pure-Java modular ring-grid index and incremental dirty-slice math for
 * {@code RotatingSectionStorage}. Eliminates signed {@code Math.floorMod} divisions
 * on every lookup and limits center-reposition updates to only the newly entered
 * X columns and Z rows instead of scanning the entire 3D grid.
 */
public final class RotatingGridMath {
	/** Immutable tear-free snapshot of a rotating grid's XZ window bounds and base modulos. */
	public record WindowState(int minX, int minZ, int baseXMod, int baseZMod) {}

	private RotatingGridMath() {}

	/**
	 * Returns {@code Math.floorMod(coord, gridSize)} using zero divisions when
	 * {@code 0 <= coord - minCoord < gridSize} and {@code baseMod == Math.floorMod(minCoord, gridSize)}.
	 */
	public static int fastModInWindow(int coord, int minCoord, int baseMod, int gridSize) {
		int slot = baseMod + (coord - minCoord);
		return slot >= gridSize ? slot - gridSize : slot;
	}

	/**
	 * Returns the world section coordinate currently mapped to modular slot {@code slot}
	 * in window {@code [minCoord, minCoord + gridSize - 1]} where
	 * {@code baseMod == Math.floorMod(minCoord, gridSize)}.
	 * Equivalent to {@code minCoord + Math.floorMod(slot - minCoord, gridSize)}.
	 */
	public static int worldCoordForSlot(int slot, int minCoord, int baseMod, int gridSize) {
		int rel = slot - baseMod;
		if (rel < 0) {
			rel += gridSize;
		}
		return minCoord + rel;
	}

	/**
	 * Returns true if slot {@code slot} (in {@code [0, gridSize)}) changed its mapped world
	 * coordinate when moving from {@code (oldBaseMod, oldMin)} to {@code (newBaseMod, newMin)}
	 * with {@code shift = newMin - oldMin} and {@code 0 < |shift| < gridSize}.
	 */
	public static boolean isSlotDirty(int slot, int oldBaseMod, int newBaseMod, int shift, int gridSize) {
		if (shift > 0) {
			int rel = slot - oldBaseMod;
			if (rel < 0) {
				rel += gridSize;
			}
			return rel < shift;
		}
		if (shift < 0) {
			int rel = slot - newBaseMod;
			if (rel < 0) {
				rel += gridSize;
			}
			return rel < -shift;
		}
		return false;
	}

	/**
	 * Given an incremental shift {@code shift = newMin - oldMin} with {@code 0 < |shift| < gridSize},
	 * returns the first dirty modular slot (the remaining {@code |shift| - 1} dirty slots follow
	 * consecutively mod {@code gridSize}).
	 */
	public static int dirtyStartSlot(int oldBaseMod, int newBaseMod, int shift) {
		return shift > 0 ? oldBaseMod : newBaseMod;
	}
}
