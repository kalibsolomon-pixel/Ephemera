package com.ephemera.engine.client;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.memory.CanonicalInterner;
import com.ephemera.core.memory.MemoryPressurePolicy;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3fc;

/**
 * Deduplicates immutable block/item model geometry during resource-pack baking:
 * <ul>
 *   <li>{@link Vector3fc} vertex positions (most quads lie on the shared 1/16 pixel grid);</li>
 *   <li>{@link BakedQuad.MaterialInfo} material/sprite/tint descriptors;</li>
 *   <li>identical {@link BakedQuad} records reused across rotated/variant/multipart block states.</li>
 * </ul>
 *
 * <p>All tables are cleared immediately once {@code ModelManager.apply} completes, so old
 * resource-pack models can never leak across F3+T reloads and steady-state table overhead is zero.
 */
public final class ModelInterning {
	private static final CanonicalInterner<Vector3fc> POSITIONS = new CanonicalInterner<>(
		1024, 32768,
		ModelInterning::hashVec3,
		ModelInterning::equalsVec3
	);

	private static final CanonicalInterner<BakedQuad.MaterialInfo> MATERIALS = new CanonicalInterner<>(
		512, 16384,
		BakedQuad.MaterialInfo::hashCode,
		BakedQuad.MaterialInfo::equals
	);

	private static final CanonicalInterner<BakedQuad> QUADS = new CanonicalInterner<>(
		2048, 65536,
		BakedQuad::hashCode,
		BakedQuad::equals
	);

	private ModelInterning() {}

	public static BakedQuad internQuad(BakedQuad quad) {
		if (quad == null || !EphemeraCommon.config().memoryDeduplicateModels) {
			return quad;
		}
		Vector3fc p0 = internPos(quad.position0());
		Vector3fc p1 = internPos(quad.position1());
		Vector3fc p2 = internPos(quad.position2());
		Vector3fc p3 = internPos(quad.position3());
		BakedQuad.MaterialInfo mat = internMaterial(quad.materialInfo());

		BakedQuad normalized = (p0 == quad.position0() && p1 == quad.position1()
			&& p2 == quad.position2() && p3 == quad.position3() && mat == quad.materialInfo())
			? quad
			: new BakedQuad(p0, p1, p2, p3,
				quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
				quad.direction(), mat);

		BakedQuad canonical = QUADS.intern(normalized);
		if (canonical != quad) {
			MemoryPressurePolicy.recordDedupSaved(1L, 80L);
		}
		return canonical;
	}

	private static Vector3fc internPos(Vector3fc pos) {
		if (pos == null) {
			return null;
		}
		Vector3fc canonical = POSITIONS.intern(pos);
		if (canonical != pos) {
			MemoryPressurePolicy.recordDedupSaved(1L, 24L);
		}
		return canonical;
	}

	private static BakedQuad.MaterialInfo internMaterial(BakedQuad.MaterialInfo mat) {
		if (mat == null) {
			return null;
		}
		BakedQuad.MaterialInfo canonical = MATERIALS.intern(mat);
		if (canonical != mat) {
			MemoryPressurePolicy.recordDedupSaved(1L, 32L);
		}
		return canonical;
	}

	/** Releases all model interning tables after resource reload completes (zero leak). */
	public static void onReloadComplete() {
		POSITIONS.clear();
		MATERIALS.clear();
		QUADS.clear();
	}

	private static int hashVec3(Vector3fc v) {
		int h = Float.floatToIntBits(v.x());
		h = 31 * h + Float.floatToIntBits(v.y());
		h = 31 * h + Float.floatToIntBits(v.z());
		return h;
	}

	private static boolean equalsVec3(Vector3fc a, Vector3fc b) {
		return Float.floatToIntBits(a.x()) == Float.floatToIntBits(b.x())
			&& Float.floatToIntBits(a.y()) == Float.floatToIntBits(b.y())
			&& Float.floatToIntBits(a.z()) == Float.floatToIntBits(b.z());
	}
}
