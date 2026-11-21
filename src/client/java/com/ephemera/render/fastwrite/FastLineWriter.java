package com.ephemera.render.fastwrite;

import com.ephemera.core.render.VertexPacking;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static org.joml.Math.fma;
import static org.lwjgl.system.MemoryUtil.memPutByte;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

/**
 * Single-reservation batched native vertex writer for {@code PrimitiveTopology.LINES} buffers using
 * {@code DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH} (24-byte stride).
 *
 * <p>Preserves Minecraft 26.2's exact {@code BufferBuilder.endLastVertex()} line-duplication invariant:
 * every logical vertex except the final pending one is written twice contiguously so mixing fast writes
 * with ordinary {@code VertexConsumer.addVertex} calls in the same buffer produces bit-identical output.
 */
public final class FastLineWriter {
	private static final int STRIDE = 24;
	private static final double CAP_DISTANCE_SQ = 16.0 * 16.0;
	private static final float SDF_ROLE = 1024.0f;

	private FastLineWriter() {}

	/**
	 * Batched fast path for a single world-space line segment (with optional SDF round caps).
	 * Returns {@code true} if handled, or {@code false} to fall back to the caller's normal path.
	 */
	public static boolean writeLine(VertexConsumer buf, Matrix4f m, Vec3 camPos,
			double x1, double y1, double z1, double x2, double y2, double z2,
			float r, float g, float b, float a, float width, boolean sdf) {
		FastBufferState builder = FastVertexBuffer.access(buf);
		if (FastVertexBuffer.layout(builder) != FastVertexLayout.LINES || m == null || camPos == null) {
			return false;
		}
		double dx = x2 - x1;
		double dy = y2 - y1;
		double dz = z2 - z1;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6) {
			return true;
		}
		int logicalCount = sdf ? 6 : 2;
		boolean hasPrev = builder.ephemera$vertices() > 0;
		int totalVerts = hasPrev ? (logicalCount << 1) : ((logicalCount << 1) - 1);
		long addr = FastVertexBuffer.reserve(builder, FastVertexLayout.LINES, totalVerts);
		if (addr == 0L) {
			return false;
		}
		if (hasPrev) {
			MemoryUtil.memCopy(addr - STRIDE, addr, STRIDE);
			addr += STRIDE;
		}

		float invLen = (float) (1.0 / len);
		int normal = VertexPacking.normal((float) (dx * invLen), (float) (dy * invLen), (float) (dz * invLen));
		int color = packFloatColor(r, g, b, a);
		if (sdf) {
			width = Math.min(width, SDF_ROLE - 1.0f);
		}

		float rx1 = (float) (x1 - camPos.x), ry1 = (float) (y1 - camPos.y), rz1 = (float) (z1 - camPos.z);
		float rx2 = (float) (x2 - camPos.x), ry2 = (float) (y2 - camPos.y), rz2 = (float) (z2 - camPos.z);
		float m00 = m.m00(), m01 = m.m01(), m02 = m.m02();
		float m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
		float m20 = m.m20(), m21 = m.m21(), m22 = m.m22();
		float m30 = m.m30(), m31 = m.m31(), m32 = m.m32();
		float p1x = fma(m00, rx1, fma(m10, ry1, fma(m20, rz1, m30)));
		float p1y = fma(m01, rx1, fma(m11, ry1, fma(m21, rz1, m31)));
		float p1z = fma(m02, rx1, fma(m12, ry1, fma(m22, rz1, m32)));
		float p2x = fma(m00, rx2, fma(m10, ry2, fma(m20, rz2, m30)));
		float p2y = fma(m01, rx2, fma(m11, ry2, fma(m21, rz2, m31)));
		float p2z = fma(m02, rx2, fma(m12, ry2, fma(m22, rz2, m32)));

		addr = putPair(addr, p1x, p1y, p1z, color, normal, width);
		if (!sdf) {
			putOne(addr, p2x, p2y, p2z, color, normal, width);
		} else {
			addr = putPair(addr, p2x, p2y, p2z, color, normal, width);
			addr = putPair(addr, p1x, p1y, p1z, color, normal, width);
			addr = putPair(addr, p1x, p1y, p1z, color, normal, width + SDF_ROLE);
			addr = putPair(addr, p2x, p2y, p2z, color, normal, width);
			putOne(addr, p2x, p2y, p2z, color, normal, width + 2.0f * SDF_ROLE);
		}
		FastVertexBuffer.finish(builder, addr, totalVerts);
		return true;
	}

	/**
	 * Batched fast path for an SDF wireframe box (12 edges plus optional round caps on all 8 corners
	 * within 16 blocks), written in a single buffer reservation with zero intermediate allocations.
	 */
	public static boolean writeSdfBox(VertexConsumer buf, Matrix4f m, Vec3 camPos,
			double x1, double y1, double z1, double x2, double y2, double z2,
			float r, float g, float b, float a, float width) {
		FastBufferState builder = FastVertexBuffer.access(buf);
		if (FastVertexBuffer.layout(builder) != FastVertexLayout.LINES || m == null || camPos == null) {
			return false;
		}
		float sx = Math.signum((float) (x2 - x1));
		float sy = Math.signum((float) (y2 - y1));
		float sz = Math.signum((float) (z2 - z1));
		int edgeVerts = (sx != 0.0f ? 8 : 0) + (sy != 0.0f ? 8 : 0) + (sz != 0.0f ? 8 : 0);
		if (edgeVerts == 0) {
			return true;
		}
		double mx = (x1 + x2) * 0.5 - camPos.x;
		double my = (y1 + y2) * 0.5 - camPos.y;
		double mz = (z1 + z2) * 0.5 - camPos.z;
		boolean caps = mx * mx + my * my + mz * mz <= CAP_DISTANCE_SQ;
		int logicalCount = edgeVerts + (caps ? 32 : 0);
		boolean hasPrev = builder.ephemera$vertices() > 0;
		int totalVerts = hasPrev ? (logicalCount << 1) : ((logicalCount << 1) - 1);
		long addr = FastVertexBuffer.reserve(builder, FastVertexLayout.LINES, totalVerts);
		if (addr == 0L) {
			return false;
		}
		if (hasPrev) {
			MemoryUtil.memCopy(addr - STRIDE, addr, STRIDE);
			addr += STRIDE;
		}

		width = Math.min(width, SDF_ROLE - 1.0f);
		int color = packFloatColor(r, g, b, a);
		int nx = sx != 0.0f ? VertexPacking.normal(sx, 0.0f, 0.0f) : 0;
		int ny = sy != 0.0f ? VertexPacking.normal(0.0f, sy, 0.0f) : 0;
		int nz = sz != 0.0f ? VertexPacking.normal(0.0f, 0.0f, sz) : 0;

		float rx1 = (float) (x1 - camPos.x), rx2 = (float) (x2 - camPos.x);
		float ry1 = (float) (y1 - camPos.y), ry2 = (float) (y2 - camPos.y);
		float rz1 = (float) (z1 - camPos.z), rz2 = (float) (z2 - camPos.z);
		float m00 = m.m00(), m01 = m.m01(), m02 = m.m02();
		float m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
		float m20 = m.m20(), m21 = m.m21(), m22 = m.m22();
		float m30 = m.m30(), m31 = m.m31(), m32 = m.m32();

		// Precompute basis contributions for the 8 corners (18 FMAs instead of 72).
		float ax0 = m00 * rx1, ax1 = m00 * rx2, ay0 = m10 * ry1, ay1 = m10 * ry2, az0 = fma(m20, rz1, m30), az1 = fma(m20, rz2, m30);
		float bx0 = m01 * rx1, bx1 = m01 * rx2, by0 = m11 * ry1, by1 = m11 * ry2, bz0 = fma(m21, rz1, m31), bz1 = fma(m21, rz2, m31);
		float cx0 = m02 * rx1, cx1 = m02 * rx2, cy0 = m12 * ry1, cy1 = m12 * ry2, cz0 = fma(m22, rz1, m32), cz1 = fma(m22, rz2, m32);

		float c0x = ax0 + ay0 + az0, c0y = bx0 + by0 + bz0, c0z = cx0 + cy0 + cz0;
		float c1x = ax1 + ay0 + az0, c1y = bx1 + by0 + bz0, c1z = cx1 + cy0 + cz0;
		float c2x = ax0 + ay1 + az0, c2y = bx0 + by1 + bz0, c2z = cx0 + cy1 + cz0;
		float c3x = ax1 + ay1 + az0, c3y = bx1 + by1 + bz0, c3z = cx1 + cy1 + cz0;
		float c4x = ax0 + ay0 + az1, c4y = bx0 + by0 + bz1, c4z = cx0 + cy0 + cz1;
		float c5x = ax1 + ay0 + az1, c5y = bx1 + by0 + bz1, c5z = cx1 + cy0 + cz1;
		float c6x = ax0 + ay1 + az1, c6y = bx0 + by1 + bz1, c6z = cx0 + cy1 + cz1;
		float c7x = ax1 + ay1 + az1, c7y = bx1 + by1 + bz1, c7z = cx1 + cy1 + cz1;

		int remaining = logicalCount;
		if (sx != 0.0f) {
			addr = emitSegment(addr, c0x, c0y, c0z, c1x, c1y, c1z, color, nx, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c2x, c2y, c2z, c3x, c3y, c3z, color, nx, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c4x, c4y, c4z, c5x, c5y, c5z, color, nx, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c6x, c6y, c6z, c7x, c7y, c7z, color, nx, width, remaining == 2); remaining -= 2;
		}
		if (sy != 0.0f) {
			addr = emitSegment(addr, c0x, c0y, c0z, c2x, c2y, c2z, color, ny, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c1x, c1y, c1z, c3x, c3y, c3z, color, ny, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c4x, c4y, c4z, c6x, c6y, c6z, color, ny, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c5x, c5y, c5z, c7x, c7y, c7z, color, ny, width, remaining == 2); remaining -= 2;
		}
		if (sz != 0.0f) {
			addr = emitSegment(addr, c0x, c0y, c0z, c4x, c4y, c4z, color, nz, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c1x, c1y, c1z, c5x, c5y, c5z, color, nz, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c2x, c2y, c2z, c6x, c6y, c6z, color, nz, width, remaining == 2); remaining -= 2;
			addr = emitSegment(addr, c3x, c3y, c3z, c7x, c7y, c7z, color, nz, width, remaining == 2); remaining -= 2;
		}
		if (caps) {
			int capNorm = sx != 0.0f ? nx : (sy != 0.0f ? ny : nz);
			float w1 = width + SDF_ROLE;
			float w2 = width + 2.0f * SDF_ROLE;
			addr = emitCap(addr, c0x, c0y, c0z, color, capNorm, width, w1, w2, false);
			addr = emitCap(addr, c1x, c1y, c1z, color, capNorm, width, w1, w2, false);
			addr = emitCap(addr, c2x, c2y, c2z, color, capNorm, width, w1, w2, false);
			addr = emitCap(addr, c3x, c3y, c3z, color, capNorm, width, w1, w2, false);
			addr = emitCap(addr, c4x, c4y, c4z, color, capNorm, width, w1, w2, false);
			addr = emitCap(addr, c5x, c5y, c5z, color, capNorm, width, w1, w2, false);
			addr = emitCap(addr, c6x, c6y, c6z, color, capNorm, width, w1, w2, false);
			addr = emitCap(addr, c7x, c7y, c7z, color, capNorm, width, w1, w2, true);
		}
		FastVertexBuffer.finish(builder, addr, totalVerts);
		return true;
	}

	private static long emitSegment(long addr, float x0, float y0, float z0, float x1, float y1, float z1,
			int color, int normal, float width, boolean isLast) {
		addr = putPair(addr, x0, y0, z0, color, normal, width);
		if (isLast) {
			putOne(addr, x1, y1, z1, color, normal, width);
			return addr;
		}
		return putPair(addr, x1, y1, z1, color, normal, width);
	}

	private static long emitCap(long addr, float x, float y, float z, int color, int normal,
			float w0, float w1, float w2, boolean isLast) {
		addr = putPair(addr, x, y, z, color, normal, w0);
		addr = putPair(addr, x, y, z, color, normal, w1);
		addr = putPair(addr, x, y, z, color, normal, w0);
		if (isLast) {
			putOne(addr, x, y, z, color, normal, w2);
			return addr;
		}
		return putPair(addr, x, y, z, color, normal, w2);
	}

	private static long putPair(long addr, float x, float y, float z, int color, int normal, float width) {
		putOne(addr, x, y, z, color, normal, width);
		putOne(addr + STRIDE, x, y, z, color, normal, width);
		return addr + (STRIDE * 2L);
	}

	private static void putOne(long addr, float x, float y, float z, int color, int normal, float width) {
		memPutFloat(addr, x);
		memPutFloat(addr + 4, y);
		memPutFloat(addr + 8, z);
		memPutInt(addr + 12, color);
		memPutByte(addr + 16, (byte) normal);
		memPutByte(addr + 17, (byte) (normal >>> 8));
		memPutByte(addr + 18, (byte) (normal >>> 16));
		memPutFloat(addr + 20, width);
	}

	private static int packFloatColor(float r, float g, float b, float a) {
		int ai = (int) (a * 255.0f) & 0xFF;
		int ri = (int) (r * 255.0f) & 0xFF;
		int gi = (int) (g * 255.0f) & 0xFF;
		int bi = (int) (b * 255.0f) & 0xFF;
		return FastVertexBuffer.packColor((ai << 24) | (ri << 16) | (gi << 8) | bi);
	}
}
