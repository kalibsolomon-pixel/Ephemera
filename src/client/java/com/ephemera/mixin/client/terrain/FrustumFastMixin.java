package com.ephemera.mixin.client.terrain;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.joml.Math.fma;

/**
 * Caches the 6 Gribb-Hartmann frustum plane equations directly in primitive fields on {@link Frustum}
 * and accelerates:
 * <ul>
 *   <li>{@link #isVisible(AABB)}: tests only the 6 p-vertices (half the dot products of vanilla's
 *       {@code intersectAab}, which needlessly also tests all 6 n-vertices).</li>
 *   <li>{@link #cubeInFrustum}: inline FMA plane tests that skip remaining n-vertex checks as soon
 *       as the box is known not to be fully inside, matching JOML's exact {@code -2 / -1 / 0..5}
 *       return codes.</li>
 *   <li>{@link #pointInFrustum}: inline FMA 6-plane test with zero indirection.</li>
 * </ul>
 */
@Mixin(Frustum.class)
abstract class FrustumFastMixin {
	@Shadow @Final private Matrix4f matrix;
	@Shadow private double camX;
	@Shadow private double camY;
	@Shadow private double camZ;

	@Unique private float ep$nxX, ep$nxY, ep$nxZ, ep$nxW;
	@Unique private float ep$pxX, ep$pxY, ep$pxZ, ep$pxW;
	@Unique private float ep$nyX, ep$nyY, ep$nyZ, ep$nyW;
	@Unique private float ep$pyX, ep$pyY, ep$pyZ, ep$pyW;
	@Unique private float ep$nzX, ep$nzY, ep$nzZ, ep$nzW;
	@Unique private float ep$pzX, ep$pzY, ep$pzZ, ep$pzW;

	@Inject(method = "calculateFrustum", at = @At("TAIL"))
	private void ephemera$extractPlanesOnCalc(Matrix4fc modelView, Matrix4f projection, CallbackInfo ci) {
		ephemera$extractPlanes(this.matrix);
	}

	@Inject(method = "set", at = @At("TAIL"))
	private void ephemera$extractPlanesOnSet(Frustum other, CallbackInfo ci) {
		ephemera$extractPlanes(this.matrix);
	}

	@Unique
	private void ephemera$extractPlanes(Matrix4f m) {
		float m03 = m.m03(), m13 = m.m13(), m23 = m.m23(), m33 = m.m33();
		float m00 = m.m00(), m10 = m.m10(), m20 = m.m20(), m30 = m.m30();
		float m01 = m.m01(), m11 = m.m11(), m21 = m.m21(), m31 = m.m31();
		float m02 = m.m02(), m12 = m.m12(), m22 = m.m22(), m32 = m.m32();
		this.ep$nxX = m03 + m00; this.ep$nxY = m13 + m10; this.ep$nxZ = m23 + m20; this.ep$nxW = m33 + m30;
		this.ep$pxX = m03 - m00; this.ep$pxY = m13 - m10; this.ep$pxZ = m23 - m20; this.ep$pxW = m33 - m30;
		this.ep$nyX = m03 + m01; this.ep$nyY = m13 + m11; this.ep$nyZ = m23 + m21; this.ep$nyW = m33 + m31;
		this.ep$pyX = m03 - m01; this.ep$pyY = m13 - m11; this.ep$pyZ = m23 - m21; this.ep$pyW = m33 - m31;
		this.ep$nzX = m03 + m02; this.ep$nzY = m13 + m12; this.ep$nzZ = m23 + m22; this.ep$nzW = m33 + m32;
		this.ep$pzX = m03 - m02; this.ep$pzY = m13 - m12; this.ep$pzZ = m23 - m22; this.ep$pzW = m33 - m32;
	}

	/**
	 * @author Ephemera
	 * @reason 6-plane p-vertex-only FMA test (skips the 6 n-vertex tests vanilla does via intersectAab).
	 */
	@Overwrite
	public boolean isVisible(AABB box) {
		float minX = (float) (box.minX - this.camX);
		float minY = (float) (box.minY - this.camY);
		float minZ = (float) (box.minZ - this.camZ);
		float maxX = (float) (box.maxX - this.camX);
		float maxY = (float) (box.maxY - this.camY);
		float maxZ = (float) (box.maxZ - this.camZ);
		return fma(ep$nxX, ep$nxX < 0f ? minX : maxX, fma(ep$nxY, ep$nxY < 0f ? minY : maxY, fma(ep$nxZ, ep$nxZ < 0f ? minZ : maxZ, ep$nxW))) >= 0f
			&& fma(ep$pxX, ep$pxX < 0f ? minX : maxX, fma(ep$pxY, ep$pxY < 0f ? minY : maxY, fma(ep$pxZ, ep$pxZ < 0f ? minZ : maxZ, ep$pxW))) >= 0f
			&& fma(ep$nyX, ep$nyX < 0f ? minX : maxX, fma(ep$nyY, ep$nyY < 0f ? minY : maxY, fma(ep$nyZ, ep$nyZ < 0f ? minZ : maxZ, ep$nyW))) >= 0f
			&& fma(ep$pyX, ep$pyX < 0f ? minX : maxX, fma(ep$pyY, ep$pyY < 0f ? minY : maxY, fma(ep$pyZ, ep$pyZ < 0f ? minZ : maxZ, ep$pyW))) >= 0f
			&& fma(ep$nzX, ep$nzX < 0f ? minX : maxX, fma(ep$nzY, ep$nzY < 0f ? minY : maxY, fma(ep$nzZ, ep$nzZ < 0f ? minZ : maxZ, ep$nzW))) >= 0f
			&& fma(ep$pzX, ep$pzX < 0f ? minX : maxX, fma(ep$pzY, ep$pzY < 0f ? minY : maxY, fma(ep$pzZ, ep$pzZ < 0f ? minZ : maxZ, ep$pzW))) >= 0f;
	}

	/**
	 * @author Ephemera
	 * @reason Inline FMA 6-plane AABB classification with lazy n-vertex checks (exact JOML return codes).
	 */
	@Overwrite
	private int cubeInFrustum(double x0, double y0, double z0, double x1, double y1, double z1) {
		float minX = (float) (x0 - this.camX);
		float minY = (float) (y0 - this.camY);
		float minZ = (float) (z0 - this.camZ);
		float maxX = (float) (x1 - this.camX);
		float maxY = (float) (y1 - this.camY);
		float maxZ = (float) (z1 - this.camZ);

		// Plane 0: -X (left)
		if (fma(ep$nxX, ep$nxX < 0f ? minX : maxX, fma(ep$nxY, ep$nxY < 0f ? minY : maxY, fma(ep$nxZ, ep$nxZ < 0f ? minZ : maxZ, ep$nxW))) < 0f) {
			return 0;
		}
		boolean inside = fma(ep$nxX, ep$nxX < 0f ? maxX : minX, fma(ep$nxY, ep$nxY < 0f ? maxY : minY, fma(ep$nxZ, ep$nxZ < 0f ? maxZ : minZ, ep$nxW))) >= 0f;

		// Plane 1: +X (right)
		if (fma(ep$pxX, ep$pxX < 0f ? minX : maxX, fma(ep$pxY, ep$pxY < 0f ? minY : maxY, fma(ep$pxZ, ep$pxZ < 0f ? minZ : maxZ, ep$pxW))) < 0f) {
			return 1;
		}
		if (inside) {
			inside = fma(ep$pxX, ep$pxX < 0f ? maxX : minX, fma(ep$pxY, ep$pxY < 0f ? maxY : minY, fma(ep$pxZ, ep$pxZ < 0f ? maxZ : minZ, ep$pxW))) >= 0f;
		}

		// Plane 2: -Y (bottom)
		if (fma(ep$nyX, ep$nyX < 0f ? minX : maxX, fma(ep$nyY, ep$nyY < 0f ? minY : maxY, fma(ep$nyZ, ep$nyZ < 0f ? minZ : maxZ, ep$nyW))) < 0f) {
			return 2;
		}
		if (inside) {
			inside = fma(ep$nyX, ep$nyX < 0f ? maxX : minX, fma(ep$nyY, ep$nyY < 0f ? maxY : minY, fma(ep$nyZ, ep$nyZ < 0f ? maxZ : minZ, ep$nyW))) >= 0f;
		}

		// Plane 3: +Y (top)
		if (fma(ep$pyX, ep$pyX < 0f ? minX : maxX, fma(ep$pyY, ep$pyY < 0f ? minY : maxY, fma(ep$pyZ, ep$pyZ < 0f ? minZ : maxZ, ep$pyW))) < 0f) {
			return 3;
		}
		if (inside) {
			inside = fma(ep$pyX, ep$pyX < 0f ? maxX : minX, fma(ep$pyY, ep$pyY < 0f ? maxY : minY, fma(ep$pyZ, ep$pyZ < 0f ? maxZ : minZ, ep$pyW))) >= 0f;
		}

		// Plane 4: -Z (near)
		if (fma(ep$nzX, ep$nzX < 0f ? minX : maxX, fma(ep$nzY, ep$nzY < 0f ? minY : maxY, fma(ep$nzZ, ep$nzZ < 0f ? minZ : maxZ, ep$nzW))) < 0f) {
			return 4;
		}
		if (inside) {
			inside = fma(ep$nzX, ep$nzX < 0f ? maxX : minX, fma(ep$nzY, ep$nzY < 0f ? maxY : minY, fma(ep$nzZ, ep$nzZ < 0f ? maxZ : minZ, ep$nzW))) >= 0f;
		}

		// Plane 5: +Z (far)
		if (fma(ep$pzX, ep$pzX < 0f ? minX : maxX, fma(ep$pzY, ep$pzY < 0f ? minY : maxY, fma(ep$pzZ, ep$pzZ < 0f ? minZ : maxZ, ep$pzW))) < 0f) {
			return 5;
		}
		if (inside) {
			inside = fma(ep$pzX, ep$pzX < 0f ? maxX : minX, fma(ep$pzY, ep$pzY < 0f ? maxY : minY, fma(ep$pzZ, ep$pzZ < 0f ? maxZ : minZ, ep$pzW))) >= 0f;
		}
		return inside ? -2 : -1;
	}

	/**
	 * @author Ephemera
	 * @reason Inline FMA 6-plane point test.
	 */
	@Overwrite
	public boolean pointInFrustum(double x, double y, double z) {
		float fx = (float) (x - this.camX);
		float fy = (float) (y - this.camY);
		float fz = (float) (z - this.camZ);
		return fma(ep$nxX, fx, fma(ep$nxY, fy, fma(ep$nxZ, fz, ep$nxW))) >= 0f
			&& fma(ep$pxX, fx, fma(ep$pxY, fy, fma(ep$pxZ, fz, ep$pxW))) >= 0f
			&& fma(ep$nyX, fx, fma(ep$nyY, fy, fma(ep$nyZ, fz, ep$nyW))) >= 0f
			&& fma(ep$pyX, fx, fma(ep$pyY, fy, fma(ep$pyZ, fz, ep$pyW))) >= 0f
			&& fma(ep$nzX, fx, fma(ep$nzY, fy, fma(ep$nzZ, fz, ep$nzW))) >= 0f
			&& fma(ep$pzX, fx, fma(ep$pzY, fy, fma(ep$pzZ, fz, ep$pzW))) >= 0f;
	}
}
