package com.ephemera.render.fastwrite;

import com.ephemera.core.render.VertexPacking;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

import static org.joml.Math.fma;
import static org.joml.Math.invsqrt;

public final class FastVertexTransforms {
	private FastVertexTransforms() {}

	public interface PoseAccess {
		boolean ephemera$trustedNormals();
	}

	static void position(long address, Matrix4fc matrix, int properties, float x, float y, float z) {
		// JOML's identity and translation branches also preserve its signed-zero behavior.
		if ((properties & Matrix4fc.PROPERTY_IDENTITY) != 0) {
			FastVertexBuffer.position(address, x, y, z);
		} else if ((properties & Matrix4fc.PROPERTY_TRANSLATION) != 0) {
			FastVertexBuffer.position(address, x + matrix.m30(), y + matrix.m31(), z + matrix.m32());
		} else {
			FastVertexBuffer.position(address,
				fma(matrix.m00(), x, fma(matrix.m10(), y, fma(matrix.m20(), z, matrix.m30()))),
				fma(matrix.m01(), x, fma(matrix.m11(), y, fma(matrix.m21(), z, matrix.m31()))),
				fma(matrix.m02(), x, fma(matrix.m12(), y, fma(matrix.m22(), z, matrix.m32()))));
		}
	}

	static int normal(PoseStack.Pose pose, boolean trusted, Vector3fc normal) {
		return normal(pose.normal(), trusted, normal.x(), normal.y(), normal.z());
	}

	static int normal(Matrix3f matrix, boolean trusted, float x, float y, float z) {
		float nx, ny, nz;
		if (x == 0.0f && z == 0.0f) {
			nx = matrix.m10() * y;
			ny = matrix.m11() * y;
			nz = matrix.m12() * y;
		} else if (y == 0.0f && z == 0.0f) {
			nx = matrix.m00() * x;
			ny = matrix.m01() * x;
			nz = matrix.m02() * x;
		} else if (x == 0.0f && y == 0.0f) {
			nx = matrix.m20() * z;
			ny = matrix.m21() * z;
			nz = matrix.m22() * z;
		} else {
			nx = fma(matrix.m00(), x, fma(matrix.m10(), y, matrix.m20() * z));
			ny = fma(matrix.m01(), x, fma(matrix.m11(), y, matrix.m21() * z));
			nz = fma(matrix.m02(), x, fma(matrix.m12(), y, matrix.m22() * z));
		}
		if (!trusted) {
			float inverseLength = invsqrt(fma(nx, nx, fma(ny, ny, nz * nz)));
			nx *= inverseLength;
			ny *= inverseLength;
			nz *= inverseLength;
		}
		return VertexPacking.normal(nx, ny, nz);
	}

	/** Negates an already-packed 3-byte signed normal (matches VertexPacking.normal(-nx, -ny, -nz)). */
	static int negatePackedNormal(int packed) {
		int bx = -(byte) packed;
		int by = -(byte) (packed >>> 8);
		int bz = -(byte) (packed >>> 16);
		return (bx & 0xFF) | ((by & 0xFF) << 8) | ((bz & 0xFF) << 16);
	}
}
