package com.ephemera.render.fastwrite;

import com.ephemera.core.render.VertexPacking;
import com.ephemera.render.fastwrite.FastVertexBuffer.SheetedDecal;
import com.ephemera.render.fastwrite.FastVertexBuffer.SpriteExpander;
import com.ephemera.render.fastwrite.FastVertexTransforms.PoseAccess;
import com.ephemera.render.terrain.VisibleSpriteTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;

import static org.joml.Math.fma;
import static org.joml.Math.invsqrt;

public final class FastQuadWriter {
	private static final float[] DECAL_UX = new float[6];
	private static final float[] DECAL_UY = new float[6];
	private static final float[] DECAL_UZ = new float[6];
	private static final float[] DECAL_VX = new float[6];
	private static final float[] DECAL_VY = new float[6];
	private static final float[] DECAL_VZ = new float[6];

	static {
		// Precompute exact orthogonal projection rows for SheetedDecalTextureGenerator.setNormal.
		Vector3f bx = new Vector3f();
		Vector3f by = new Vector3f();
		Vector3f bz = new Vector3f();
		for (Direction dir : Direction.values()) {
			int o = dir.ordinal();
			var rot = dir.getRotation();
			bx.set(1, 0, 0).rotateY((float) Math.PI).rotateX((float) (-Math.PI / 2)).rotate(rot);
			by.set(0, 1, 0).rotateY((float) Math.PI).rotateX((float) (-Math.PI / 2)).rotate(rot);
			bz.set(0, 0, 1).rotateY((float) Math.PI).rotateX((float) (-Math.PI / 2)).rotate(rot);
			DECAL_UX[o] = -bx.x;
			DECAL_UY[o] = -by.x;
			DECAL_UZ[o] = -bz.x;
			DECAL_VX[o] = -bx.y;
			DECAL_VY[o] = -by.y;
			DECAL_VZ[o] = -bz.y;
		}
	}

	private FastQuadWriter() {}

	public static boolean write(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		return pose != null && write(consumer, pose, 0, 0, 0, quad, instance);
	}

	public static boolean writeBlock(VertexConsumer consumer, float x, float y, float z, BakedQuad quad, QuadInstance instance) {
		return write(consumer, null, x, y, z, quad, instance);
	}

	/**
	 * Batched fast path for {@code ItemFeatureRenderer.prepareMainSubmit} over a contiguous run of
	 * quads sharing one {@code RenderType} and {@code PoseStack.Pose}: 1 buffer reservation,
	 * 1 pose-matrix load, lazily cached 6-direction normals, and zero per-quad {@code QuadInstance}
	 * switches.
	 */
	public static boolean writeItemRun(VertexConsumer consumer, PoseStack.Pose pose, List<BakedQuad> quads,
			int start, int end, int[] tintLayers, int lightCoords, int overlayCoords, boolean trackSprites) {
		int count = end - start;
		if (count <= 0 || pose == null || !((Object) pose instanceof PoseAccess access)) {
			return false;
		}
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if (layout != FastVertexLayout.ENTITY) {
			return false;
		}
		for (int i = start; i < end; i++) {
			BakedQuad q = quads.get(i);
			if (q.position0() == null || q.position1() == null || q.position2() == null || q.position3() == null) {
				return false;
			}
		}
		long address = FastVertexBuffer.reserve(builder, layout, count * 4);
		if (address == 0L) {
			return false;
		}
		int stride = layout.stride;
		int packedOverlay = FastVertexBuffer.packUv(overlayCoords);
		int packedBaseLight = FastVertexBuffer.packUv(lightCoords);
		Matrix3f normalMat = pose.normal();
		boolean trusted = access.ephemera$trustedNormals();
		int normalMask = 0;
		int n0 = 0, n1 = 0, n2 = 0, n3 = 0, n4 = 0, n5 = 0;

		Matrix4f matrix = pose.pose();
		int props = matrix.properties();
		boolean identity = (props & Matrix4fc.PROPERTY_IDENTITY) != 0;
		boolean translation = !identity && (props & Matrix4fc.PROPERTY_TRANSLATION) != 0;
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

		for (int i = start; i < end; i++) {
			BakedQuad q = quads.get(i);
			if (trackSprites) {
				VisibleSpriteTracker.markExternal(q);
			}
			BakedQuad.MaterialInfo info = q.materialInfo();
			int packedColor = -1;
			if (info.isTinted()) {
				int ti = info.tintIndex();
				if (ti >= 0 && ti < tintLayers.length) {
					packedColor = FastVertexBuffer.packColor(tintLayers[ti]);
				}
			}
			int emission = info.lightEmission();
			int packedLight = emission == 0 ? packedBaseLight
				: FastVertexBuffer.packUv(LightCoordsUtil.lightCoordsWithEmission(lightCoords, emission));

			int dirOrd = q.direction().ordinal();
			int bit = 1 << dirOrd;
			int normal;
			if ((normalMask & bit) == 0) {
				normal = FastVertexTransforms.cardinalNormal(normalMat, trusted, q.direction());
				normalMask |= bit;
				switch (dirOrd) {
					case 0 -> n0 = normal;
					case 1 -> n1 = normal;
					case 2 -> n2 = normal;
					case 3 -> n3 = normal;
					case 4 -> n4 = normal;
					default -> n5 = normal;
				}
			} else {
				normal = switch (dirOrd) {
					case 0 -> n0;
					case 1 -> n1;
					case 2 -> n2;
					case 3 -> n3;
					case 4 -> n4;
					default -> n5;
				};
			}

			Vector3fc p0 = q.position0(), p1 = q.position1(), p2 = q.position2(), p3 = q.position3();
			if (identity) {
				FastVertexBuffer.position(address, p0.x(), p0.y(), p0.z());
				long uv0 = q.packedUV(0);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv0), UVPair.unpackV(uv0), packedOverlay, packedLight, normal);
				address += stride;

				FastVertexBuffer.position(address, p1.x(), p1.y(), p1.z());
				long uv1 = q.packedUV(1);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv1), UVPair.unpackV(uv1), packedOverlay, packedLight, normal);
				address += stride;

				FastVertexBuffer.position(address, p2.x(), p2.y(), p2.z());
				long uv2 = q.packedUV(2);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv2), UVPair.unpackV(uv2), packedOverlay, packedLight, normal);
				address += stride;

				FastVertexBuffer.position(address, p3.x(), p3.y(), p3.z());
				long uv3 = q.packedUV(3);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv3), UVPair.unpackV(uv3), packedOverlay, packedLight, normal);
				address += stride;
			} else if (translation) {
				FastVertexBuffer.position(address, p0.x() + m30, p0.y() + m31, p0.z() + m32);
				long uv0 = q.packedUV(0);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv0), UVPair.unpackV(uv0), packedOverlay, packedLight, normal);
				address += stride;

				FastVertexBuffer.position(address, p1.x() + m30, p1.y() + m31, p1.z() + m32);
				long uv1 = q.packedUV(1);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv1), UVPair.unpackV(uv1), packedOverlay, packedLight, normal);
				address += stride;

				FastVertexBuffer.position(address, p2.x() + m30, p2.y() + m31, p2.z() + m32);
				long uv2 = q.packedUV(2);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv2), UVPair.unpackV(uv2), packedOverlay, packedLight, normal);
				address += stride;

				FastVertexBuffer.position(address, p3.x() + m30, p3.y() + m31, p3.z() + m32);
				long uv3 = q.packedUV(3);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv3), UVPair.unpackV(uv3), packedOverlay, packedLight, normal);
				address += stride;
			} else {
				float x0 = p0.x(), y0 = p0.y(), z0 = p0.z();
				FastVertexBuffer.position(address,
					fma(m00, x0, fma(m10, y0, fma(m20, z0, m30))),
					fma(m01, x0, fma(m11, y0, fma(m21, z0, m31))),
					fma(m02, x0, fma(m12, y0, fma(m22, z0, m32))));
				long uv0 = q.packedUV(0);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv0), UVPair.unpackV(uv0), packedOverlay, packedLight, normal);
				address += stride;

				float x1 = p1.x(), y1 = p1.y(), z1 = p1.z();
				FastVertexBuffer.position(address,
					fma(m00, x1, fma(m10, y1, fma(m20, z1, m30))),
					fma(m01, x1, fma(m11, y1, fma(m21, z1, m31))),
					fma(m02, x1, fma(m12, y1, fma(m22, z1, m32))));
				long uv1 = q.packedUV(1);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv1), UVPair.unpackV(uv1), packedOverlay, packedLight, normal);
				address += stride;

				float x2 = p2.x(), y2 = p2.y(), z2 = p2.z();
				FastVertexBuffer.position(address,
					fma(m00, x2, fma(m10, y2, fma(m20, z2, m30))),
					fma(m01, x2, fma(m11, y2, fma(m21, z2, m31))),
					fma(m02, x2, fma(m12, y2, fma(m22, z2, m32))));
				long uv2 = q.packedUV(2);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv2), UVPair.unpackV(uv2), packedOverlay, packedLight, normal);
				address += stride;

				float x3 = p3.x(), y3 = p3.y(), z3 = p3.z();
				FastVertexBuffer.position(address,
					fma(m00, x3, fma(m10, y3, fma(m20, z3, m30))),
					fma(m01, x3, fma(m11, y3, fma(m21, z3, m31))),
					fma(m02, x3, fma(m12, y3, fma(m22, z3, m32))));
				long uv3 = q.packedUV(3);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv3), UVPair.unpackV(uv3), packedOverlay, packedLight, normal);
				address += stride;
			}
		}
		FastVertexBuffer.finish(builder, address - stride, count * 4);
		return true;
	}

	/**
	 * Batched fast path for standard item enchantment glint ({@code FoilType.STANDARD}), using
	 * uniform {@code QuadInstance} state across the contiguous glint run.
	 */
	public static boolean writeFoilRun(VertexConsumer consumer, PoseStack.Pose pose, List<BakedQuad> quads,
			int start, int end, QuadInstance instance) {
		int count = end - start;
		if (count <= 0 || pose == null || !((Object) pose instanceof PoseAccess access)) {
			return false;
		}
		if (consumer instanceof SheetedDecal decal) {
			return writeSheetedDecalRun(decal, pose, quads, start, end, instance);
		}
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if (layout != FastVertexLayout.ENTITY && layout != FastVertexLayout.POSITION_TEX) {
			return false;
		}
		for (int i = start; i < end; i++) {
			BakedQuad q = quads.get(i);
			if (q.position0() == null || q.position1() == null || q.position2() == null || q.position3() == null) {
				return false;
			}
		}
		long address = FastVertexBuffer.reserve(builder, layout, count * 4);
		if (address == 0L) {
			return false;
		}
		int stride = layout.stride;
		Matrix4f matrix = pose.pose();
		int props = matrix.properties();
		boolean identity = (props & Matrix4fc.PROPERTY_IDENTITY) != 0;
		boolean translation = !identity && (props & Matrix4fc.PROPERTY_TRANSLATION) != 0;
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
		if (layout == FastVertexLayout.POSITION_TEX) {
			for (int i = start; i < end; i++) {
				BakedQuad q = quads.get(i);
				Vector3fc p0 = q.position0(), p1 = q.position1(), p2 = q.position2(), p3 = q.position3();
				if (identity) {
					writePosTex(address, p0.x(), p0.y(), p0.z(), q.packedUV(0)); address += stride;
					writePosTex(address, p1.x(), p1.y(), p1.z(), q.packedUV(1)); address += stride;
					writePosTex(address, p2.x(), p2.y(), p2.z(), q.packedUV(2)); address += stride;
					writePosTex(address, p3.x(), p3.y(), p3.z(), q.packedUV(3)); address += stride;
				} else if (translation) {
					writePosTex(address, p0.x() + m30, p0.y() + m31, p0.z() + m32, q.packedUV(0)); address += stride;
					writePosTex(address, p1.x() + m30, p1.y() + m31, p1.z() + m32, q.packedUV(1)); address += stride;
					writePosTex(address, p2.x() + m30, p2.y() + m31, p2.z() + m32, q.packedUV(2)); address += stride;
					writePosTex(address, p3.x() + m30, p3.y() + m31, p3.z() + m32, q.packedUV(3)); address += stride;
				} else {
					float x0 = p0.x(), y0 = p0.y(), z0 = p0.z();
					writePosTex(address, fma(m00, x0, fma(m10, y0, fma(m20, z0, m30))),
						fma(m01, x0, fma(m11, y0, fma(m21, z0, m31))),
						fma(m02, x0, fma(m12, y0, fma(m22, z0, m32))), q.packedUV(0)); address += stride;
					float x1 = p1.x(), y1 = p1.y(), z1 = p1.z();
					writePosTex(address, fma(m00, x1, fma(m10, y1, fma(m20, z1, m30))),
						fma(m01, x1, fma(m11, y1, fma(m21, z1, m31))),
						fma(m02, x1, fma(m12, y1, fma(m22, z1, m32))), q.packedUV(1)); address += stride;
					float x2 = p2.x(), y2 = p2.y(), z2 = p2.z();
					writePosTex(address, fma(m00, x2, fma(m10, y2, fma(m20, z2, m30))),
						fma(m01, x2, fma(m11, y2, fma(m21, z2, m31))),
						fma(m02, x2, fma(m12, y2, fma(m22, z2, m32))), q.packedUV(2)); address += stride;
					float x3 = p3.x(), y3 = p3.y(), z3 = p3.z();
					writePosTex(address, fma(m00, x3, fma(m10, y3, fma(m20, z3, m30))),
						fma(m01, x3, fma(m11, y3, fma(m21, z3, m31))),
						fma(m02, x3, fma(m12, y3, fma(m22, z3, m32))), q.packedUV(3)); address += stride;
				}
			}
			FastVertexBuffer.finish(builder, address - stride, count * 4);
			return true;
		}
		int packedColor = FastVertexBuffer.packColor(instance.getColor(0));
		int baseLight = instance.getLightCoords(0);
		int packedBaseLight = FastVertexBuffer.packUv(baseLight);
		int packedOverlay = FastVertexBuffer.packUv(instance.overlayCoords());
		Matrix3f normalMat = pose.normal();
		boolean trusted = access.ephemera$trustedNormals();
		int normalMask = 0;
		int n0 = 0, n1 = 0, n2 = 0, n3 = 0, n4 = 0, n5 = 0;
		for (int i = start; i < end; i++) {
			BakedQuad q = quads.get(i);
			int emission = q.materialInfo().lightEmission();
			int packedLight = emission == 0 ? packedBaseLight
				: FastVertexBuffer.packUv(LightCoordsUtil.lightCoordsWithEmission(baseLight, emission));
			int dirOrd = q.direction().ordinal();
			int bit = 1 << dirOrd;
			int normal;
			if ((normalMask & bit) == 0) {
				normal = FastVertexTransforms.cardinalNormal(normalMat, trusted, q.direction());
				normalMask |= bit;
				switch (dirOrd) {
					case 0 -> n0 = normal;
					case 1 -> n1 = normal;
					case 2 -> n2 = normal;
					case 3 -> n3 = normal;
					case 4 -> n4 = normal;
					default -> n5 = normal;
				}
			} else {
				normal = switch (dirOrd) {
					case 0 -> n0;
					case 1 -> n1;
					case 2 -> n2;
					case 3 -> n3;
					case 4 -> n4;
					default -> n5;
				};
			}
			Vector3fc p0 = q.position0(), p1 = q.position1(), p2 = q.position2(), p3 = q.position3();
			if (identity) {
				FastVertexBuffer.position(address, p0.x(), p0.y(), p0.z());
				long uv0 = q.packedUV(0);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv0), UVPair.unpackV(uv0), packedOverlay, packedLight, normal);
				address += stride;
				FastVertexBuffer.position(address, p1.x(), p1.y(), p1.z());
				long uv1 = q.packedUV(1);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv1), UVPair.unpackV(uv1), packedOverlay, packedLight, normal);
				address += stride;
				FastVertexBuffer.position(address, p2.x(), p2.y(), p2.z());
				long uv2 = q.packedUV(2);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv2), UVPair.unpackV(uv2), packedOverlay, packedLight, normal);
				address += stride;
				FastVertexBuffer.position(address, p3.x(), p3.y(), p3.z());
				long uv3 = q.packedUV(3);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv3), UVPair.unpackV(uv3), packedOverlay, packedLight, normal);
				address += stride;
			} else if (translation) {
				FastVertexBuffer.position(address, p0.x() + m30, p0.y() + m31, p0.z() + m32);
				long uv0 = q.packedUV(0);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv0), UVPair.unpackV(uv0), packedOverlay, packedLight, normal);
				address += stride;
				FastVertexBuffer.position(address, p1.x() + m30, p1.y() + m31, p1.z() + m32);
				long uv1 = q.packedUV(1);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv1), UVPair.unpackV(uv1), packedOverlay, packedLight, normal);
				address += stride;
				FastVertexBuffer.position(address, p2.x() + m30, p2.y() + m31, p2.z() + m32);
				long uv2 = q.packedUV(2);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv2), UVPair.unpackV(uv2), packedOverlay, packedLight, normal);
				address += stride;
				FastVertexBuffer.position(address, p3.x() + m30, p3.y() + m31, p3.z() + m32);
				long uv3 = q.packedUV(3);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv3), UVPair.unpackV(uv3), packedOverlay, packedLight, normal);
				address += stride;
			} else {
				float x0 = p0.x(), y0 = p0.y(), z0 = p0.z();
				FastVertexBuffer.position(address, fma(m00, x0, fma(m10, y0, fma(m20, z0, m30))),
					fma(m01, x0, fma(m11, y0, fma(m21, z0, m31))), fma(m02, x0, fma(m12, y0, fma(m22, z0, m32))));
				long uv0 = q.packedUV(0);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv0), UVPair.unpackV(uv0), packedOverlay, packedLight, normal);
				address += stride;
				float x1 = p1.x(), y1 = p1.y(), z1 = p1.z();
				FastVertexBuffer.position(address, fma(m00, x1, fma(m10, y1, fma(m20, z1, m30))),
					fma(m01, x1, fma(m11, y1, fma(m21, z1, m31))), fma(m02, x1, fma(m12, y1, fma(m22, z1, m32))));
				long uv1 = q.packedUV(1);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv1), UVPair.unpackV(uv1), packedOverlay, packedLight, normal);
				address += stride;
				float x2 = p2.x(), y2 = p2.y(), z2 = p2.z();
				FastVertexBuffer.position(address, fma(m00, x2, fma(m10, y2, fma(m20, z2, m30))),
					fma(m01, x2, fma(m11, y2, fma(m21, z2, m31))), fma(m02, x2, fma(m12, y2, fma(m22, z2, m32))));
				long uv2 = q.packedUV(2);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv2), UVPair.unpackV(uv2), packedOverlay, packedLight, normal);
				address += stride;
				float x3 = p3.x(), y3 = p3.y(), z3 = p3.z();
				FastVertexBuffer.position(address, fma(m00, x3, fma(m10, y3, fma(m20, z3, m30))),
					fma(m01, x3, fma(m11, y3, fma(m21, z3, m31))), fma(m02, x3, fma(m12, y3, fma(m22, z3, m32))));
				long uv3 = q.packedUV(3);
				FastVertexBuffer.attributesEntity(address, packedColor, UVPair.unpackU(uv3), UVPair.unpackV(uv3), packedOverlay, packedLight, normal);
				address += stride;
			}
		}
		FastVertexBuffer.finish(builder, address - stride, count * 4);
		return true;
	}

	private static void writePosTex(long address, float x, float y, float z, long uv) {
		FastVertexBuffer.position(address, x, y, z);
		org.lwjgl.system.MemoryUtil.memPutFloat(address + 12, UVPair.unpackU(uv));
		org.lwjgl.system.MemoryUtil.memPutFloat(address + 16, UVPair.unpackV(uv));
	}

	private static boolean writeSheetedDecalRun(SheetedDecal decal, PoseStack.Pose pose, List<BakedQuad> quads,
			int start, int end, QuadInstance instance) {
		int count = end - start;
		if (count <= 0 || pose == null || !((Object) pose instanceof PoseAccess access)) {
			return false;
		}
		FastBufferState builder = FastVertexBuffer.access(decal.ephemera$delegate());
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if (layout == null || !layout.model) {
			return false;
		}
		for (int i = start; i < end; i++) {
			BakedQuad q = quads.get(i);
			if (q.position0() == null || q.position1() == null || q.position2() == null || q.position3() == null) {
				return false;
			}
		}
		long address = FastVertexBuffer.reserve(builder, layout, count * 4);
		if (address == 0L) {
			return false;
		}
		Matrix3f normalMat = pose.normal();
		boolean trusted = access.ephemera$trustedNormals();
		Matrix3f invNorm = decal.ephemera$normalInversePose();
		float scale = decal.ephemera$textureScale();
		Matrix4f poseMat = pose.pose();
		int poseProps = poseMat.properties();
		boolean identity = (poseProps & Matrix4fc.PROPERTY_IDENTITY) != 0;
		boolean translation = !identity && (poseProps & Matrix4fc.PROPERTY_TRANSLATION) != 0;
		float m00 = 0, m01 = 0, m02 = 0, m10 = 0, m11 = 0, m12 = 0, m20 = 0, m21 = 0, m22 = 0;
		float m30 = 0, m31 = 0, m32 = 0;
		if (!identity) {
			m30 = poseMat.m30(); m31 = poseMat.m31(); m32 = poseMat.m32();
			if (!translation) {
				m00 = poseMat.m00(); m01 = poseMat.m01(); m02 = poseMat.m02();
				m10 = poseMat.m10(); m11 = poseMat.m11(); m12 = poseMat.m12();
				m20 = poseMat.m20(); m21 = poseMat.m21(); m22 = poseMat.m22();
			}
		}
		Matrix4f camInv = decal.ephemera$cameraInversePose();
		float c00 = camInv.m00(), c01 = camInv.m01(), c02 = camInv.m02();
		float c10 = camInv.m10(), c11 = camInv.m11(), c12 = camInv.m12();
		float c20 = camInv.m20(), c21 = camInv.m21(), c22 = camInv.m22();
		float c30 = camInv.m30(), c31 = camInv.m31(), c32 = camInv.m32();

		int packedOverlay = layout.overlay >= 0 ? FastVertexBuffer.packUv(instance.overlayCoords()) : 0;
		int baseLight = instance.getLightCoords(0);
		int packedBaseLight = layout.light >= 0 ? FastVertexBuffer.packUv(baseLight) : 0;
		boolean isEntity = layout == FastVertexLayout.ENTITY;
		boolean hasLight = layout.light >= 0;
		boolean hasNormal = layout.normal >= 0;
		int stride = layout.stride;

		// Lazily cache per-cardinal-direction packed normal and projected decal face ordinal (max 6 entries).
		int dirMask = 0;
		int[] cachedNormal = new int[6];
		int[] cachedDecalOrd = new int[6];

		for (int qIdx = start; qIdx < end; qIdx++) {
			BakedQuad quad = quads.get(qIdx);
			int dirOrd = quad.direction().ordinal();
			int bit = 1 << dirOrd;
			int packedNormal;
			int ord;
			if ((dirMask & bit) == 0) {
				Vector3fc dirVec = quad.direction().getUnitVec3f();
				float dx = dirVec.x(), dy = dirVec.y(), dz = dirVec.z();
				float nx, ny, nz;
				if (dx == 0.0f && dz == 0.0f) {
					nx = normalMat.m10() * dy; ny = normalMat.m11() * dy; nz = normalMat.m12() * dy;
				} else if (dy == 0.0f && dz == 0.0f) {
					nx = normalMat.m00() * dx; ny = normalMat.m01() * dx; nz = normalMat.m02() * dx;
				} else if (dx == 0.0f && dy == 0.0f) {
					nx = normalMat.m20() * dz; ny = normalMat.m21() * dz; nz = normalMat.m22() * dz;
				} else {
					nx = fma(normalMat.m00(), dx, fma(normalMat.m10(), dy, normalMat.m20() * dz));
					ny = fma(normalMat.m01(), dx, fma(normalMat.m11(), dy, normalMat.m21() * dz));
					nz = fma(normalMat.m02(), dx, fma(normalMat.m12(), dy, normalMat.m22() * dz));
				}
				if (!trusted) {
					float inv = invsqrt(fma(nx, nx, fma(ny, ny, nz * nz)));
					nx *= inv; ny *= inv; nz *= inv;
				}
				packedNormal = hasNormal ? VertexPacking.normal(nx, ny, nz) : 0;
				float inx = fma(invNorm.m00(), nx, fma(invNorm.m10(), ny, invNorm.m20() * nz));
				float iny = fma(invNorm.m01(), nx, fma(invNorm.m11(), ny, invNorm.m21() * nz));
				float inz = fma(invNorm.m02(), nx, fma(invNorm.m12(), ny, invNorm.m22() * nz));
				ord = Direction.getApproximateNearest(inx, iny, inz).ordinal();
				cachedNormal[dirOrd] = packedNormal;
				cachedDecalOrd[dirOrd] = ord;
				dirMask |= bit;
			} else {
				packedNormal = cachedNormal[dirOrd];
				ord = cachedDecalOrd[dirOrd];
			}
			float ux = DECAL_UX[ord] * scale, uy = DECAL_UY[ord] * scale, uz = DECAL_UZ[ord] * scale;
			float vx = DECAL_VX[ord] * scale, vy = DECAL_VY[ord] * scale, vz = DECAL_VZ[ord] * scale;
			int emission = quad.materialInfo().lightEmission();
			int packedLight = !hasLight ? 0 : (emission == 0 ? packedBaseLight
				: FastVertexBuffer.packUv(LightCoordsUtil.lightCoordsWithEmission(baseLight, emission)));

			for (int i = 0; i < 4; i++, address += stride) {
				Vector3fc p = quad.position(i);
				float px, py, pz;
				if (identity) {
					px = p.x(); py = p.y(); pz = p.z();
				} else if (translation) {
					px = p.x() + m30; py = p.y() + m31; pz = p.z() + m32;
				} else {
					float rx = p.x(), ry = p.y(), rz = p.z();
					px = fma(m00, rx, fma(m10, ry, fma(m20, rz, m30)));
					py = fma(m01, rx, fma(m11, ry, fma(m21, rz, m31)));
					pz = fma(m02, rx, fma(m12, ry, fma(m22, rz, m32)));
				}
				FastVertexBuffer.position(address, px, py, pz);
				float wx = fma(c00, px, fma(c10, py, fma(c20, pz, c30)));
				float wy = fma(c01, px, fma(c11, py, fma(c21, pz, c31)));
				float wz = fma(c02, px, fma(c12, py, fma(c22, pz, c32)));
				float u = fma(ux, wx, fma(uy, wy, uz * wz));
				float v = fma(vx, wx, fma(vy, wy, vz * wz));
				if (isEntity) {
					FastVertexBuffer.attributesEntity(address, -1, u, v, packedOverlay, packedLight, packedNormal);
				} else {
					FastVertexBuffer.attributes(address, layout, -1, u, v, packedLight, packedOverlay, packedNormal);
				}
			}
		}
		FastVertexBuffer.finish(builder, address - stride, count * 4);
		return true;
	}

	public static boolean writeSheetedDecal(SheetedDecal decal, PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		if (pose == null || quad.position0() == null || quad.position1() == null
				|| quad.position2() == null || quad.position3() == null) {
			return false;
		}
		FastBufferState builder = FastVertexBuffer.access(decal.ephemera$delegate());
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if (layout == null || !layout.model || !((Object) pose instanceof PoseAccess access)) {
			return false;
		}
		Vector3fc dirVec = quad.direction().getUnitVec3f();
		Matrix3f normalMat = pose.normal();
		float nx, ny, nz;
		float dx = dirVec.x(), dy = dirVec.y(), dz = dirVec.z();
		if (dx == 0.0f && dz == 0.0f) {
			nx = normalMat.m10() * dy; ny = normalMat.m11() * dy; nz = normalMat.m12() * dy;
		} else if (dy == 0.0f && dz == 0.0f) {
			nx = normalMat.m00() * dx; ny = normalMat.m01() * dx; nz = normalMat.m02() * dx;
		} else if (dx == 0.0f && dy == 0.0f) {
			nx = normalMat.m20() * dz; ny = normalMat.m21() * dz; nz = normalMat.m22() * dz;
		} else {
			nx = fma(normalMat.m00(), dx, fma(normalMat.m10(), dy, normalMat.m20() * dz));
			ny = fma(normalMat.m01(), dx, fma(normalMat.m11(), dy, normalMat.m21() * dz));
			nz = fma(normalMat.m02(), dx, fma(normalMat.m12(), dy, normalMat.m22() * dz));
		}
		if (!access.ephemera$trustedNormals()) {
			float inv = invsqrt(fma(nx, nx, fma(ny, ny, nz * nz)));
			nx *= inv; ny *= inv; nz *= inv;
		}
		int packedNormal = layout.normal >= 0 ? VertexPacking.normal(nx, ny, nz) : 0;

		Matrix3f invNorm = decal.ephemera$normalInversePose();
		float inx = fma(invNorm.m00(), nx, fma(invNorm.m10(), ny, invNorm.m20() * nz));
		float iny = fma(invNorm.m01(), nx, fma(invNorm.m11(), ny, invNorm.m21() * nz));
		float inz = fma(invNorm.m02(), nx, fma(invNorm.m12(), ny, invNorm.m22() * nz));
		int ord = Direction.getApproximateNearest(inx, iny, inz).ordinal();
		float scale = decal.ephemera$textureScale();
		float ux = DECAL_UX[ord] * scale, uy = DECAL_UY[ord] * scale, uz = DECAL_UZ[ord] * scale;
		float vx = DECAL_VX[ord] * scale, vy = DECAL_VY[ord] * scale, vz = DECAL_VZ[ord] * scale;

		Matrix4f poseMat = pose.pose();
		int poseProps = poseMat.properties();
		Matrix4f camInv = decal.ephemera$cameraInversePose();
		int emission = quad.materialInfo().lightEmission();
		int packedOverlay = layout.overlay >= 0 ? FastVertexBuffer.packUv(instance.overlayCoords()) : 0;
		// SheetedDecalTextureGenerator.setColor always writes -1 (opaque white).
		int packedWhite = -1;

		long address = FastVertexBuffer.reserve(builder, layout, 4);
		if (address == 0) {
			return false;
		}
		int stride = layout.stride;
		for (int i = 0; i < 4; i++, address += stride) {
			Vector3fc p = quad.position(i);
			float px, py, pz;
			if ((poseProps & Matrix4fc.PROPERTY_IDENTITY) != 0) {
				px = p.x(); py = p.y(); pz = p.z();
			} else if ((poseProps & Matrix4fc.PROPERTY_TRANSLATION) != 0) {
				px = p.x() + poseMat.m30(); py = p.y() + poseMat.m31(); pz = p.z() + poseMat.m32();
			} else {
				px = fma(poseMat.m00(), p.x(), fma(poseMat.m10(), p.y(), fma(poseMat.m20(), p.z(), poseMat.m30())));
				py = fma(poseMat.m01(), p.x(), fma(poseMat.m11(), p.y(), fma(poseMat.m21(), p.z(), poseMat.m31())));
				pz = fma(poseMat.m02(), p.x(), fma(poseMat.m12(), p.y(), fma(poseMat.m22(), p.z(), poseMat.m32())));
			}
			FastVertexBuffer.position(address, px, py, pz);

			float wx = fma(camInv.m00(), px, fma(camInv.m10(), py, fma(camInv.m20(), pz, camInv.m30())));
			float wy = fma(camInv.m01(), px, fma(camInv.m11(), py, fma(camInv.m21(), pz, camInv.m31())));
			float wz = fma(camInv.m02(), px, fma(camInv.m12(), py, fma(camInv.m22(), pz, camInv.m32())));
			float u = fma(ux, wx, fma(uy, wy, uz * wz));
			float v = fma(vx, wx, fma(vy, wy, vz * wz));

			int light = layout.light >= 0 ? FastVertexBuffer.packUv(
				emission == 0 ? instance.getLightCoords(i) : instance.getLightCoordsWithEmission(i, emission)) : 0;
			if (layout == FastVertexLayout.ENTITY) {
				FastVertexBuffer.attributesEntity(address, packedWhite, u, v, packedOverlay, light, packedNormal);
			} else {
				FastVertexBuffer.attributes(address, layout, packedWhite, u, v, light, packedOverlay, packedNormal);
			}
		}
		FastVertexBuffer.finish(builder, address - stride, 4);
		return true;
	}

	private static boolean write(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z,
			BakedQuad quad, QuadInstance instance) {
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
		Vector3fc p0, p1, p2, p3;
		if (layout == null || !layout.model || (p0 = quad.position0()) == null || (p1 = quad.position1()) == null
				|| (p2 = quad.position2()) == null || (p3 = quad.position3()) == null) {
			return false;
		}
		int normal = 0;
		if (layout.normal >= 0) {
			Direction dir = quad.direction();
			if (pose == null) {
				normal = FastVertexTransforms.cardinalNormalIdentity(dir);
			} else if ((Object) pose instanceof PoseAccess access) {
				normal = FastVertexTransforms.cardinalNormal(pose.normal(), access.ephemera$trustedNormals(), dir);
			} else {
				return false;
			}
		}
		int emission = layout.light >= 0 ? quad.materialInfo().lightEmission() : 0;
		int packedOverlay = layout.overlay >= 0 ? FastVertexBuffer.packUv(instance.overlayCoords()) : 0;
		Matrix4f matrix = pose == null ? null : pose.pose();
		int properties = matrix == null ? 0 : matrix.properties();
		boolean identity = matrix != null && (properties & Matrix4fc.PROPERTY_IDENTITY) != 0;
		boolean translation = matrix != null && !identity && (properties & Matrix4fc.PROPERTY_TRANSLATION) != 0;
		float m00 = 0, m01 = 0, m02 = 0, m10 = 0, m11 = 0, m12 = 0, m20 = 0, m21 = 0, m22 = 0;
		float m30 = 0, m31 = 0, m32 = 0;
		if (matrix != null && !identity) {
			m30 = matrix.m30(); m31 = matrix.m31(); m32 = matrix.m32();
			if (!translation) {
				m00 = matrix.m00(); m01 = matrix.m01(); m02 = matrix.m02();
				m10 = matrix.m10(); m11 = matrix.m11(); m12 = matrix.m12();
				m20 = matrix.m20(); m21 = matrix.m21(); m22 = matrix.m22();
			}
		}
		long address = FastVertexBuffer.reserve(builder, layout, 4);
		if (address == 0) {
			return false;
		}
		int stride = layout.stride;
		if (!remapUv) {
			if (layout == FastVertexLayout.ENTITY) {
				if (matrix == null) {
					writeEntityOffset(address, stride, p0, p1, p2, p3, quad, instance, emission, packedOverlay, normal, x, y, z);
				} else if (identity) {
					writeEntityIdentity(address, stride, p0, p1, p2, p3, quad, instance, emission, packedOverlay, normal);
				} else if (translation) {
					writeEntityOffset(address, stride, p0, p1, p2, p3, quad, instance, emission, packedOverlay, normal, m30, m31, m32);
				} else {
					writeEntityMatrix(address, stride, p0, p1, p2, p3, quad, instance, emission, packedOverlay, normal,
						m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32);
				}
				FastVertexBuffer.finish(builder, address + stride * 3L, 4);
				return true;
			}
			if (layout == FastVertexLayout.BLOCK && matrix == null) {
				writeBlockVert(address, p0, quad.packedUV(0), instance, 0, emission, x, y, z);
				writeBlockVert(address + stride, p1, quad.packedUV(1), instance, 1, emission, x, y, z);
				writeBlockVert(address + stride * 2L, p2, quad.packedUV(2), instance, 2, emission, x, y, z);
				long last = address + stride * 3L;
				writeBlockVert(last, p3, quad.packedUV(3), instance, 3, emission, x, y, z);
				FastVertexBuffer.finish(builder, last, 4);
				return true;
			}
		}
		boolean isEntity = layout == FastVertexLayout.ENTITY;
		boolean isBlock = layout == FastVertexLayout.BLOCK;
		boolean hasColor = layout.color >= 0;
		boolean hasLight = layout.light >= 0;
		for (int i = 0; i < 4; i++, address += stride) {
			Vector3fc position = switch (i) {
				case 0 -> p0;
				case 1 -> p1;
				case 2 -> p2;
				default -> p3;
			};
			long uv = quad.packedUV(i);
			float px = position.x(), py = position.y(), pz = position.z();
			if (matrix == null) {
				FastVertexBuffer.position(address, px + x, py + y, pz + z);
			} else if (identity) {
				FastVertexBuffer.position(address, px, py, pz);
			} else if (translation) {
				FastVertexBuffer.position(address, px + m30, py + m31, pz + m32);
			} else {
				FastVertexBuffer.position(address,
					fma(m00, px, fma(m10, py, fma(m20, pz, m30))),
					fma(m01, px, fma(m11, py, fma(m21, pz, m31))),
					fma(m02, px, fma(m12, py, fma(m22, pz, m32))));
			}
			float u = UVPair.unpackU(uv);
			float v = UVPair.unpackV(uv);
			if (remapUv) {
				u = fma(u, du, u0);
				v = fma(v, dv, v0);
			}
			if (isEntity) {
				int rawLight = emission == 0 ? instance.getLightCoords(i) : instance.getLightCoordsWithEmission(i, emission);
				FastVertexBuffer.attributesEntity(address, FastVertexBuffer.packColor(instance.getColor(i)),
					u, v, packedOverlay, FastVertexBuffer.packUv(rawLight), normal);
			} else if (isBlock) {
				int rawLight = emission == 0 ? instance.getLightCoords(i) : instance.getLightCoordsWithEmission(i, emission);
				FastVertexBuffer.attributesBlock(address, FastVertexBuffer.packColor(instance.getColor(i)),
					u, v, FastVertexBuffer.packUv(rawLight));
			} else {
				int packedColor = hasColor ? FastVertexBuffer.packColor(instance.getColor(i)) : 0;
				int packedLight = hasLight ? FastVertexBuffer.packUv(
					emission == 0 ? instance.getLightCoords(i) : instance.getLightCoordsWithEmission(i, emission)) : 0;
				FastVertexBuffer.attributes(address, layout, packedColor, u, v, packedLight, packedOverlay, normal);
			}
		}
		FastVertexBuffer.finish(builder, address - stride, 4);
		return true;
	}

	private static void writeEntityMatrix(long address, int stride, Vector3fc p0, Vector3fc p1, Vector3fc p2, Vector3fc p3,
			BakedQuad quad, QuadInstance instance, int emission, int packedOverlay, int normal,
			float m00, float m01, float m02, float m10, float m11, float m12,
			float m20, float m21, float m22, float m30, float m31, float m32) {
		float x0 = p0.x(), y0 = p0.y(), z0 = p0.z();
		FastVertexBuffer.position(address,
			fma(m00, x0, fma(m10, y0, fma(m20, z0, m30))),
			fma(m01, x0, fma(m11, y0, fma(m21, z0, m31))),
			fma(m02, x0, fma(m12, y0, fma(m22, z0, m32))));
		writeEntityAttrs(address, quad.packedUV(0), instance, 0, emission, packedOverlay, normal);
		address += stride;

		float x1 = p1.x(), y1 = p1.y(), z1 = p1.z();
		FastVertexBuffer.position(address,
			fma(m00, x1, fma(m10, y1, fma(m20, z1, m30))),
			fma(m01, x1, fma(m11, y1, fma(m21, z1, m31))),
			fma(m02, x1, fma(m12, y1, fma(m22, z1, m32))));
		writeEntityAttrs(address, quad.packedUV(1), instance, 1, emission, packedOverlay, normal);
		address += stride;

		float x2 = p2.x(), y2 = p2.y(), z2 = p2.z();
		FastVertexBuffer.position(address,
			fma(m00, x2, fma(m10, y2, fma(m20, z2, m30))),
			fma(m01, x2, fma(m11, y2, fma(m21, z2, m31))),
			fma(m02, x2, fma(m12, y2, fma(m22, z2, m32))));
		writeEntityAttrs(address, quad.packedUV(2), instance, 2, emission, packedOverlay, normal);
		address += stride;

		float x3 = p3.x(), y3 = p3.y(), z3 = p3.z();
		FastVertexBuffer.position(address,
			fma(m00, x3, fma(m10, y3, fma(m20, z3, m30))),
			fma(m01, x3, fma(m11, y3, fma(m21, z3, m31))),
			fma(m02, x3, fma(m12, y3, fma(m22, z3, m32))));
		writeEntityAttrs(address, quad.packedUV(3), instance, 3, emission, packedOverlay, normal);
	}

	private static void writeEntityOffset(long address, int stride, Vector3fc p0, Vector3fc p1, Vector3fc p2, Vector3fc p3,
			BakedQuad quad, QuadInstance instance, int emission, int packedOverlay, int normal,
			float ox, float oy, float oz) {
		FastVertexBuffer.position(address, p0.x() + ox, p0.y() + oy, p0.z() + oz);
		writeEntityAttrs(address, quad.packedUV(0), instance, 0, emission, packedOverlay, normal);
		address += stride;

		FastVertexBuffer.position(address, p1.x() + ox, p1.y() + oy, p1.z() + oz);
		writeEntityAttrs(address, quad.packedUV(1), instance, 1, emission, packedOverlay, normal);
		address += stride;

		FastVertexBuffer.position(address, p2.x() + ox, p2.y() + oy, p2.z() + oz);
		writeEntityAttrs(address, quad.packedUV(2), instance, 2, emission, packedOverlay, normal);
		address += stride;

		FastVertexBuffer.position(address, p3.x() + ox, p3.y() + oy, p3.z() + oz);
		writeEntityAttrs(address, quad.packedUV(3), instance, 3, emission, packedOverlay, normal);
	}

	private static void writeEntityIdentity(long address, int stride, Vector3fc p0, Vector3fc p1, Vector3fc p2, Vector3fc p3,
			BakedQuad quad, QuadInstance instance, int emission, int packedOverlay, int normal) {
		FastVertexBuffer.position(address, p0.x(), p0.y(), p0.z());
		writeEntityAttrs(address, quad.packedUV(0), instance, 0, emission, packedOverlay, normal);
		address += stride;

		FastVertexBuffer.position(address, p1.x(), p1.y(), p1.z());
		writeEntityAttrs(address, quad.packedUV(1), instance, 1, emission, packedOverlay, normal);
		address += stride;

		FastVertexBuffer.position(address, p2.x(), p2.y(), p2.z());
		writeEntityAttrs(address, quad.packedUV(2), instance, 2, emission, packedOverlay, normal);
		address += stride;

		FastVertexBuffer.position(address, p3.x(), p3.y(), p3.z());
		writeEntityAttrs(address, quad.packedUV(3), instance, 3, emission, packedOverlay, normal);
	}

	private static void writeEntityAttrs(long address, long uv, QuadInstance instance, int i,
			int emission, int packedOverlay, int normal) {
		int rawLight = emission == 0 ? instance.getLightCoords(i) : instance.getLightCoordsWithEmission(i, emission);
		FastVertexBuffer.attributesEntity(address, FastVertexBuffer.packColor(instance.getColor(i)),
			UVPair.unpackU(uv), UVPair.unpackV(uv), packedOverlay, FastVertexBuffer.packUv(rawLight), normal);
	}

	private static void writeBlockVert(long address, Vector3fc pos, long uv, QuadInstance instance, int i,
			int emission, float x, float y, float z) {
		FastVertexBuffer.position(address, pos.x() + x, pos.y() + y, pos.z() + z);
		int rawLight = emission == 0 ? instance.getLightCoords(i) : instance.getLightCoordsWithEmission(i, emission);
		FastVertexBuffer.attributesBlock(address, FastVertexBuffer.packColor(instance.getColor(i)),
			UVPair.unpackU(uv), UVPair.unpackV(uv), FastVertexBuffer.packUv(rawLight));
	}
}
