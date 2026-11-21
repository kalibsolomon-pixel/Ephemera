package com.ephemera.mixin.client.terrain;

import com.ephemera.core.terrain.RotatingGridMath;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Accelerates {@link RotatingSectionStorage} with:
 * <ul>
 *   <li>A flat direct {@code Value[]} mirror eliminating {@code Node} indirection.</li>
 *   <li>Zero-division modular lookups via an immutable volatile {@code WindowState} snapshot
 *       (100% tear-free for background occlusion-graph readers).</li>
 *   <li>Incremental dirty-column/row {@link #repositionCenter} updates (~30x fewer section
 *       updates on chunk-border crossings, O(1) on Y-only movement).</li>
 * </ul>
 */
@Mixin(RotatingSectionStorage.class)
abstract class RotatingSectionStorageFastMixin<T extends RotatingSectionStorage.Value> {
	@Shadow @Final private int radius;
	@Shadow @Final private int minY;
	@Shadow @Final private int sectionGridSizeY;
	@Shadow @Final private int sectionGridSizeXZ;
	@Shadow private SectionPos centerSectionPos;

	@Shadow public abstract int size();
	@Shadow public abstract void forEach(Consumer<? super T> action);

	@Unique private RotatingSectionStorage.Value[] ephemera$values;
	@Unique private volatile RotatingGridMath.@Nullable WindowState ephemera$window;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$mirrorValues(int radius, int minY, int maxY,
									   RotatingSectionStorage.ValueCreator<T> creator, CallbackInfo ci) {
		RotatingSectionStorage.Value[] flat = new RotatingSectionStorage.Value[this.size()];
		int[] idx = {0};
		this.forEach(v -> flat[idx[0]++] = v);
		this.ephemera$values = flat;
	}

	/**
	 * @author Ephemera
	 * @reason Incremental dirty-slice reposition without scanning unchanged sections or calling floorMod per cell.
	 */
	@Overwrite
	public boolean repositionCenter(SectionPos pos) {
		if (pos.equals(this.centerSectionPos)) {
			return false;
		}
		int gridXZ = this.sectionGridSizeXZ;
		int gridY = this.sectionGridSizeY;
		int baseMinY = this.minY;
		int newMinX = pos.x() - this.radius;
		int newMinZ = pos.z() - this.radius;
		int newBaseX = Math.floorMod(newMinX, gridXZ);
		int newBaseZ = Math.floorMod(newMinZ, gridXZ);
		RotatingGridMath.WindowState prev = this.ephemera$window;
		RotatingSectionStorage.Value[] vals = this.ephemera$values;

		if (prev != null) {
			int shiftX = newMinX - prev.minX();
			int shiftZ = newMinZ - prev.minZ();
			if (shiftX == 0 && shiftZ == 0) {
				this.centerSectionPos = pos;
				return true;
			}
			if (Math.abs(shiftX) < gridXZ && Math.abs(shiftZ) < gridXZ) {
				int strideZ = gridY * gridXZ;
				if (shiftX != 0) {
					int countX = Math.abs(shiftX);
					int gx = RotatingGridMath.dirtyStartSlot(prev.baseXMod(), newBaseX, shiftX);
					for (int kx = 0; kx < countX; kx++) {
						int wx = RotatingGridMath.worldCoordForSlot(gx, newMinX, newBaseX, gridXZ);
						for (int gz = 0; gz < gridXZ; gz++) {
							int wz = RotatingGridMath.worldCoordForSlot(gz, newMinZ, newBaseZ, gridXZ);
							int idx = gz * strideZ + gx;
							for (int gy = 0; gy < gridY; gy++, idx += gridXZ) {
								long target = SectionPos.asLong(wx, baseMinY + gy, wz);
								RotatingSectionStorage.Value v = vals[idx];
								if (v.getSectionNode() != target) {
									v.setSectionNode(target);
								}
							}
						}
						if (++gx == gridXZ) {
							gx = 0;
						}
					}
				}
				if (shiftZ != 0) {
					int countZ = Math.abs(shiftZ);
					int gz = RotatingGridMath.dirtyStartSlot(prev.baseZMod(), newBaseZ, shiftZ);
					for (int kz = 0; kz < countZ; kz++) {
						int wz = RotatingGridMath.worldCoordForSlot(gz, newMinZ, newBaseZ, gridXZ);
						int rowBase = gz * strideZ;
						for (int gx = 0; gx < gridXZ; gx++) {
							if (shiftX != 0 && RotatingGridMath.isSlotDirty(gx, prev.baseXMod(), newBaseX, shiftX, gridXZ)) {
								continue;
							}
							int wx = RotatingGridMath.worldCoordForSlot(gx, newMinX, newBaseX, gridXZ);
							int idx = rowBase + gx;
							for (int gy = 0; gy < gridY; gy++, idx += gridXZ) {
								long target = SectionPos.asLong(wx, baseMinY + gy, wz);
								RotatingSectionStorage.Value v = vals[idx];
								if (v.getSectionNode() != target) {
									v.setSectionNode(target);
								}
							}
						}
						if (++gz == gridXZ) {
							gz = 0;
						}
					}
				}
				this.centerSectionPos = pos;
				this.ephemera$window = new RotatingGridMath.WindowState(newMinX, newMinZ, newBaseX, newBaseZ);
				return true;
			}
		}

		// Full update (initial positioning or large teleport) without inner floorMod divisions
		int strideZ = gridY * gridXZ;
		for (int gz = 0; gz < gridXZ; gz++) {
			int wz = RotatingGridMath.worldCoordForSlot(gz, newMinZ, newBaseZ, gridXZ);
			int rowBase = gz * strideZ;
			for (int gx = 0; gx < gridXZ; gx++) {
				int wx = RotatingGridMath.worldCoordForSlot(gx, newMinX, newBaseX, gridXZ);
				int idx = rowBase + gx;
				for (int gy = 0; gy < gridY; gy++, idx += gridXZ) {
					long target = SectionPos.asLong(wx, baseMinY + gy, wz);
					RotatingSectionStorage.Value v = vals[idx];
					if (v.getSectionNode() != target) {
						v.setSectionNode(target);
					}
				}
			}
		}
		this.centerSectionPos = pos;
		this.ephemera$window = new RotatingGridMath.WindowState(newMinX, newMinZ, newBaseX, newBaseZ);
		return true;
	}

	/**
	 * @author Ephemera
	 * @reason Zero-division window bounds check + direct flat Value[] lookup.
	 */
	@SuppressWarnings("unchecked")
	@Overwrite
	public @Nullable T getValue(int x, int y, int z) {
		RotatingGridMath.WindowState w = this.ephemera$window;
		if (w == null) {
			return null;
		}
		int dy = y - this.minY;
		int gridY = this.sectionGridSizeY;
		if (Integer.compareUnsigned(dy, gridY) >= 0) {
			return null;
		}
		int gridXZ = this.sectionGridSizeXZ;
		int dx = x - w.minX();
		if (Integer.compareUnsigned(dx, gridXZ) >= 0) {
			return null;
		}
		int dz = z - w.minZ();
		if (Integer.compareUnsigned(dz, gridXZ) >= 0) {
			return null;
		}
		int gx = w.baseXMod() + dx;
		if (gx >= gridXZ) {
			gx -= gridXZ;
		}
		int gz = w.baseZMod() + dz;
		if (gz >= gridXZ) {
			gz -= gridXZ;
		}
		return (T) this.ephemera$values[(gz * gridY + dy) * gridXZ + gx];
	}
}
