package com.ephemera.render.fastwrite;

import com.ephemera.core.render.VertexPacking;
import com.ephemera.render.fastwrite.FastVertexBuffer.SpriteExpander;
import com.ephemera.render.fastwrite.FastVertexTransforms.PoseAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

import static java.lang.Float.floatToRawIntBits;
import static org.joml.Math.fma;
import static org.joml.Math.invsqrt;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutInt;

public final class FastCuboidWriter {
	// Each nibble names a corner in the DOWN (0–3) or UP (4–7) face, in vanilla's face order.
	private static final int[] SIDE_CORNERS = {0x5612, 0x4523, 0x7430, 0x6701};

	private FastCuboidWriter() {}

	/**
	 * Fast 3-edge-vector 8-corner register path for precompiled standard {@link ModelPart.Cube}s.
	 * Zero polygon/vertex object chasing, zero per-vertex division by 16, zero copyCorner checks.
	 */
	public static boolean writeCompiled(VertexConsumer consumer, PoseStack.Pose pose, CompiledCuboid c,
			ModelPart.Polygon[] polygons, int light, int overlay, int color) {
		if (!c.matches(polygons)) {
			return false;
		}
		float u0 = 0.0f, du = 1.0f, v0 = 0.0f, dv = 1.0f;
		boolean remapUv = false;
		if (consumer instanceof SpriteExpander expander) {
			TextureAtlasSprite sprite = expander.ephemera$sprite();
			if (sprite == null) {
				return false;
			}
			consumer = expander.ephemera$delegate();
			u0 = sprite.getU0();
			du = sprite.getU1() - u0;
			v0 = sprite.getV0();
			dv = sprite.getV1() - v0;
			remapUv = true;
		}
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if (layout == null || !layout.model) {
			return false;
		}
		int nPosX = 0, nNegX = 0, nPosY = 0, nNegY = 0, nPosZ = 0, nNegZ = 0;
		if (layout.normal >= 0) {
			if (!((Object) pose instanceof PoseAccess access)) {
				return false;
			}
			boolean trusted = access.ephemera$trustedNormals();
			Matrix3f nm = pose.normal();
			int am = c.axisMask;
			if ((am & 1) != 0) {
				nPosX = packAxisNormal(nm.m00(), nm.m01(), nm.m02(), trusted);
				nNegX = FastVertexTransforms.negatePackedNormal(nPosX);
			}
			if ((am & 2) != 0) {
				nPosY = packAxisNormal(nm.m10(), nm.m11(), nm.m12(), trusted);
				nNegY = FastVertexTransforms.negatePackedNormal(nPosY);
			}
			if ((am & 4) != 0) {
				nPosZ = packAxisNormal(nm.m20(), nm.m21(), nm.m22(), trusted);
				nNegZ = FastVertexTransforms.negatePackedNormal(nPosZ);
			}
		}

		int faceCount = c.faceCount;
		int vertexCount = faceCount * 4;
		long address = FastVertexBuffer.reserve(builder, layout, vertexCount);
		if (address == 0) {
			return false;
		}

		// Transform 1 origin + 3 edge vectors -> derive all 8 corners in registers.
		Matrix4f m = pose.pose();
		int props = m.properties();
		float cx0, cy0, cz0, exX, exY, exZ, eyX, eyY, eyZ, ezX, ezY, ezZ;
		if ((props & Matrix4fc.PROPERTY_IDENTITY) != 0) {
			cx0 = c.x0; cy0 = c.y0; cz0 = c.z0;
			exX = c.dx; exY = 0; exZ = 0;
			eyX = 0; eyY = c.dy; eyZ = 0;
			ezX = 0; ezY = 0; ezZ = c.dz;
		} else if ((props & Matrix4fc.PROPERTY_TRANSLATION) != 0) {
			cx0 = c.x0 + m.m30(); cy0 = c.y0 + m.m31(); cz0 = c.z0 + m.m32();
			exX = c.dx; exY = 0; exZ = 0;
			eyX = 0; eyY = c.dy; eyZ = 0;
			ezX = 0; ezY = 0; ezZ = c.dz;
		} else {
			float m00 = m.m00(), m01 = m.m01(), m02 = m.m02();
			float m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
			float m20 = m.m20(), m21 = m.m21(), m22 = m.m22();
			cx0 = fma(m00, c.x0, fma(m10, c.y0, fma(m20, c.z0, m.m30())));
			cy0 = fma(m01, c.x0, fma(m11, c.y0, fma(m21, c.z0, m.m31())));
			cz0 = fma(m02, c.x0, fma(m12, c.y0, fma(m22, c.z0, m.m32())));
			exX = m00 * c.dx; exY = m01 * c.dx; exZ = m02 * c.dx;
			eyX = m10 * c.dy; eyY = m11 * c.dy; eyZ = m12 * c.dy;
			ezX = m20 * c.dz; ezY = m21 * c.dz; ezZ = m22 * c.dz;
		}
		float p0x = cx0, p0y = cy0, p0z = cz0;
		float p1x = cx0 + exX, p1y = cy0 + exY, p1z = cz0 + exZ;
		float p2x = cx0 + eyX, p2y = cy0 + eyY, p2z = cz0 + eyZ;
		float p3x = p1x + eyX, p3y = p1y + eyY, p3z = p1z + eyZ;
		float p4x = cx0 + ezX, p4y = cy0 + ezY, p4z = cz0 + ezZ;
		float p5x = p1x + ezX, p5y = p1y + ezY, p5z = p1z + ezZ;
		float p6x = p2x + ezX, p6y = p2y + ezY, p6z = p2z + ezZ;
		float p7x = p3x + ezX, p7y = p3y + ezY, p7z = p3z + ezZ;

		int stride = layout.stride;
		boolean isEntity = layout == FastVertexLayout.ENTITY;
		int packedColor = FastVertexBuffer.packColor(color);
		int packedLight = FastVertexBuffer.packUv(light);
		int packedOverlay = FastVertexBuffer.packUv(overlay);
		int[] cornerPacks = c.cornerPacks;
		byte[] normalCodes = c.normalCodes;
		float[] uvs = c.uvs;

		for (int f = 0; f < faceCount; f++) {
			int normal = switch (normalCodes[f]) {
				case CompiledCuboid.POS_X -> nPosX;
				case CompiledCuboid.NEG_X -> nNegX;
				case CompiledCuboid.POS_Y -> nPosY;
				case CompiledCuboid.NEG_Y -> nNegY;
				case CompiledCuboid.POS_Z -> nPosZ;
				default -> nNegZ;
			};
			int pack = cornerPacks[f];
			int uvOff = f * 8;
			for (int corner = 0; corner < 4; corner++, address += stride) {
				int idx = (pack >>> (corner * 4)) & 7;
				switch (idx) {
					case 0 -> FastVertexBuffer.position(address, p0x, p0y, p0z);
					case 1 -> FastVertexBuffer.position(address, p1x, p1y, p1z);
					case 2 -> FastVertexBuffer.position(address, p2x, p2y, p2z);
					case 3 -> FastVertexBuffer.position(address, p3x, p3y, p3z);
					case 4 -> FastVertexBuffer.position(address, p4x, p4y, p4z);
					case 5 -> FastVertexBuffer.position(address, p5x, p5y, p5z);
					case 6 -> FastVertexBuffer.position(address, p6x, p6y, p6z);
					default -> FastVertexBuffer.position(address, p7x, p7y, p7z);
				}
				float u = uvs[uvOff + corner * 2];
				float v = uvs[uvOff + corner * 2 + 1];
				if (remapUv) {
					u = fma(u, du, u0);
					v = fma(v, dv, v0);
				}
				if (isEntity) {
					FastVertexBuffer.attributesEntity(address, packedColor, u, v, packedOverlay, packedLight, normal);
				} else {
					FastVertexBuffer.attributes(address, layout, packedColor, u, v, packedLight, packedOverlay, normal);
				}
			}
		}
		FastVertexBuffer.finish(builder, address - stride, vertexCount);
		return true;
	}

	private static int packAxisNormal(float nx, float ny, float nz, boolean trusted) {
		if (!trusted) {
			float inv = invsqrt(fma(nx, nx, fma(ny, ny, nz * nz)));
			nx *= inv; ny *= inv; nz *= inv;
		}
		return VertexPacking.normal(nx, ny, nz);
	}

	public static boolean write(VertexConsumer consumer, PoseStack.Pose pose, ModelPart.Polygon[] polygons,
			int light, int overlay, int color) {
		return write(consumer, pose, polygons, light, overlay, color, false);
	}

	public static boolean write(VertexConsumer consumer, PoseStack.Pose pose, ModelPart.Polygon[] polygons,
			int light, int overlay, int color, boolean mirrored) {
		float u0 = 0.0f, du = 1.0f, v0 = 0.0f, dv = 1.0f;
		boolean remapUv = false;
		if (consumer instanceof SpriteExpander expander) {
			TextureAtlasSprite sprite = expander.ephemera$sprite();
			if (sprite == null) {
				return false;
			}
			consumer = expander.ephemera$delegate();
			u0 = sprite.getU0();
			du = sprite.getU1() - u0;
			v0 = sprite.getV0();
			dv = sprite.getV1() - v0;
			remapUv = true;
		}
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		int faceCount = polygons.length;
		if (layout == null || !layout.model || faceCount == 0 || faceCount > 6) {
			return false;
		}
		boolean trustedNormals = false;
		Matrix3f normalMatrix = null;
		if (layout.normal >= 0) {
			if (!((Object) pose instanceof PoseAccess access)) {
				return false;
			}
			trustedNormals = access.ephemera$trustedNormals();
			normalMatrix = pose.normal();
		}
		for (ModelPart.Polygon polygon : polygons) {
			if (polygon == null || polygon.normal() == null) {
				return false;
			}
			ModelPart.Vertex[] vertices = polygon.vertices();
			if (vertices == null || vertices.length != 4
					|| vertices[0] == null || vertices[1] == null || vertices[2] == null || vertices[3] == null) {
				return false;
			}
		}
		Matrix4f matrix = pose.pose();
		int properties = matrix.properties();
		boolean identity = (properties & org.joml.Matrix4fc.PROPERTY_IDENTITY) != 0;
		boolean translation = !identity && (properties & org.joml.Matrix4fc.PROPERTY_TRANSLATION) != 0;
		float m00 = 0, m01 = 0, m02 = 0, m10 = 0, m11 = 0, m12 = 0, m20 = 0, m21 = 0, m22 = 0;
		float m30 = 0, m31 = 0, m32 = 0;
		if (!identity) {
			m30 = matrix.m30(); m31 = matrix.m31(); m32 = matrix.m32();
			if (!translation) {
				m00 = matrix.m00(); m01 = matrix.m01(); m02 = matrix.m02();
				m10 = matrix.m10(); m11 = matrix.m11(); m12 = matrix.m12();
				m20 = matrix.m20(); m21 = matrix.m21(); m22 = matrix.m22();
			}
		}
		int vertexCount = faceCount * 4;
		long start = FastVertexBuffer.reserve(builder, layout, vertexCount);
		if (start == 0) {
			return false;
		}
		int stride = layout.stride;
		boolean isEntity = layout == FastVertexLayout.ENTITY;
		int packedColor = FastVertexBuffer.packColor(color);
		int packedLight = FastVertexBuffer.packUv(light);
		int packedOverlay = FastVertexBuffer.packUv(overlay);
		long address = start;
		int n0 = 0, n2 = 0, n3 = 0;
		for (int face = 0; face < faceCount; face++) {
			ModelPart.Polygon polygon = polygons[face];
			int normal = 0;
			if (normalMatrix != null) {
				Vector3fc pn = polygon.normal();
				if (face == 1 && isOpposite(pn, polygons[0].normal())) {
					normal = FastVertexTransforms.negatePackedNormal(n0);
				} else if (face == 4 && isOpposite(pn, polygons[2].normal())) {
					normal = FastVertexTransforms.negatePackedNormal(n2);
				} else if (face == 5 && isOpposite(pn, polygons[3].normal())) {
					normal = FastVertexTransforms.negatePackedNormal(n3);
				} else {
					normal = FastVertexTransforms.normal(normalMatrix, trustedNormals, pn.x(), pn.y(), pn.z());
					if (face == 0) n0 = normal;
					else if (face == 2) n2 = normal;
					else if (face == 3) n3 = normal;
				}
			}
			ModelPart.Vertex[] vertices = polygon.vertices();
			for (int corner = 0; corner < 4; corner++, address += stride) {
				ModelPart.Vertex vertex = vertices[corner];
				if (faceCount != 6 || face < 2
						|| !copyCorner(start, address, stride, polygons, vertex, face, corner, mirrored)) {
					float vx = vertex.worldX(), vy = vertex.worldY(), vz = vertex.worldZ();
					if (identity) {
						FastVertexBuffer.position(address, vx, vy, vz);
					} else if (translation) {
						FastVertexBuffer.position(address, vx + m30, vy + m31, vz + m32);
					} else {
						FastVertexBuffer.position(address,
							fma(m00, vx, fma(m10, vy, fma(m20, vz, m30))),
							fma(m01, vx, fma(m11, vy, fma(m21, vz, m31))),
							fma(m02, vx, fma(m12, vy, fma(m22, vz, m32))));
					}
				}
				float u = remapUv ? fma(vertex.u(), du, u0) : vertex.u();
				float v = remapUv ? fma(vertex.v(), dv, v0) : vertex.v();
				if (isEntity) {
					FastVertexBuffer.attributesEntity(address, packedColor, u, v, packedOverlay, packedLight, normal);
				} else {
					FastVertexBuffer.attributes(address, layout, packedColor, u, v, packedLight, packedOverlay, normal);
				}
			}
		}
		FastVertexBuffer.finish(builder, address - stride, vertexCount);
		return true;
	}

	private static boolean isOpposite(Vector3fc a, Vector3fc b) {
		return a.x() == -b.x() && a.y() == -b.y() && a.z() == -b.z();
	}

	private static boolean copyCorner(long start, long address, int stride, ModelPart.Polygon[] polygons,
			ModelPart.Vertex vertex, int face, int corner, boolean mirrored) {
		int reversal = mirrored ? 3 : 0;
		int source = (SIDE_CORNERS[face - 2] >>> ((corner ^ reversal) * 4) & 7) ^ reversal;
		ModelPart.Vertex original = polygons[source >>> 2].vertices()[source & 3];
		// Polygon arrays are mutable. Reuse only when the current inputs match bit for bit.
		if (floatToRawIntBits(original.worldX()) != floatToRawIntBits(vertex.worldX())
				|| floatToRawIntBits(original.worldY()) != floatToRawIntBits(vertex.worldY())
				|| floatToRawIntBits(original.worldZ()) != floatToRawIntBits(vertex.worldZ())) {
			return false;
		}
		long sourceAddress = start + source * stride;
		memPutInt(address, memGetInt(sourceAddress));
		memPutInt(address + 4, memGetInt(sourceAddress + 4));
		memPutInt(address + 8, memGetInt(sourceAddress + 8));
		return true;
	}
}
