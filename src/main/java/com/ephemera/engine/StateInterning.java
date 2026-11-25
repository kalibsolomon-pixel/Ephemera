package com.ephemera.engine;

import com.ephemera.core.memory.CanonicalInterner;
import com.ephemera.core.memory.MemoryPressurePolicy;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.SliceShape;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.List;

/**
 * High-hit-rate, bounded canonical interning pools for immutable block-state caches,
 * {@link VoxelShape}s, axis coordinate {@link DoubleList}s, occlusion-face arrays,
 * face-support flags, and {@code StateHolder} property-value arrays.
 *
 * <p>Replaces small direct-mapped arrays that thrashed on vanilla's 6,500+ distinct state caches,
 * achieving near-100% deduplication while supporting {@link #trim()} after bootstrap / world load
 * so the interning tables themselves retain almost zero heap overhead and never pin stale objects.
 */
public final class StateInterning {
	private static final Axis[] AXES = Axis.values();

	private static final CanonicalInterner<boolean[]> SUPPORTS = new CanonicalInterner<>(
		256, 4096,
		Arrays::hashCode,
		Arrays::equals
	);

	private static final CanonicalInterner<VoxelShape[]> FACES = new CanonicalInterner<>(
		512, 16384,
		StateInterning::hashShapeArray,
		StateInterning::equalsShapeArray
	);

	private static final CanonicalInterner<StateCacheView> CACHES = new CanonicalInterner<>(
		1024, 32768,
		view -> view.ephemera$key().hashCode(),
		(a, b) -> a.ephemera$key().equals(b.ephemera$key())
	);

	private static final CanonicalInterner<VoxelShape> SHAPES = new CanonicalInterner<>(
		1024, 32768,
		StateInterning::hashVoxelShape,
		StateInterning::equalsVoxelShape
	);

	private static final CanonicalInterner<DoubleList> DOUBLE_LISTS = new CanonicalInterner<>(
		256, 8192,
		StateInterning::hashDoubleList,
		StateInterning::equalsDoubleList
	);

	private static final CanonicalInterner<Comparable<?>[]> PROPERTY_VALUES = new CanonicalInterner<>(
		1024, 32768,
		StateInterning::hashIdentityArray,
		StateInterning::equalsIdentityArray
	);

	private StateInterning() {}

	public static boolean[] supports(boolean[] values) {
		if (values == null || values.length == 0) {
			return values;
		}
		boolean[] canonical = SUPPORTS.intern(values);
		if (canonical != values) {
			MemoryPressurePolicy.recordDedupSaved(1L, 24L);
		}
		return canonical;
	}

	public static VoxelShape[] faces(VoxelShape[] values) {
		if (values == null || values.length == 0) {
			return values;
		}
		VoxelShape[] canonical = FACES.intern(values);
		if (canonical != values) {
			MemoryPressurePolicy.recordDedupSaved(1L, 16L + (long) values.length * 4L);
		}
		return canonical;
	}

	public static Object cache(Object cache) {
		if (!(cache instanceof StateCacheView view)) {
			return cache;
		}
		StateCacheView canonical = CACHES.intern(view);
		if (canonical != view) {
			MemoryPressurePolicy.recordDedupSaved(1L, 48L);
		}
		return canonical;
	}

	public static VoxelShape shape(VoxelShape value) {
		if (value == null || value == Shapes.empty() || value == Shapes.block()) {
			return value;
		}
		Class<?> type = value.getClass();
		if (type != ArrayVoxelShape.class && type != CubeVoxelShape.class && type != SliceShape.class) {
			return value;
		}
		VoxelShape canonical = SHAPES.intern(value);
		if (canonical != value) {
			MemoryPressurePolicy.recordDedupSaved(1L, 96L);
		}
		return canonical;
	}

	public static DoubleList doubleList(DoubleList list) {
		if (list == null || list.getClass() != DoubleArrayList.class) {
			return list;
		}
		int n = list.size();
		if (n == 0 || n > 16 || list.getDouble(0) < -1.0 || list.getDouble(n - 1) > 2.0) {
			return list;
		}
		DoubleList canonical = DOUBLE_LISTS.intern(list);
		if (canonical != list) {
			MemoryPressurePolicy.recordDedupSaved(1L, 32L + (long) n * 8L);
		}
		return canonical;
	}

	public static Comparable<?>[] propertyValues(Comparable<?>[] values) {
		if (values == null || values.length == 0) {
			return values;
		}
		Comparable<?>[] canonical = PROPERTY_VALUES.intern(values);
		if (canonical != values) {
			MemoryPressurePolicy.recordDedupSaved(1L, 16L + (long) values.length * 4L);
		}
		return canonical;
	}

	/**
	 * Clears transient lookup tables once static block-state bootstrap is complete so the tables
	 * themselves do not retain extra heap memory during gameplay.
	 */
	public static void trim() {
		SUPPORTS.clear();
		FACES.clear();
		CACHES.clear();
		SHAPES.clear();
		DOUBLE_LISTS.clear();
		PROPERTY_VALUES.clear();
	}

	public static long totalDeduplicated() {
		return SUPPORTS.deduplicatedCount()
			+ FACES.deduplicatedCount()
			+ CACHES.deduplicatedCount()
			+ SHAPES.deduplicatedCount()
			+ DOUBLE_LISTS.deduplicatedCount()
			+ PROPERTY_VALUES.deduplicatedCount();
	}

	private static int hashShapeArray(VoxelShape[] values) {
		int h = 1;
		for (VoxelShape face : values) {
			h = 31 * h + System.identityHashCode(face);
		}
		return h;
	}

	private static boolean equalsShapeArray(VoxelShape[] a, VoxelShape[] b) {
		if (a.length != b.length) {
			return false;
		}
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}

	private static int hashVoxelShape(VoxelShape value) {
		// The mixin-applied cache makes repeat probes (and collision re-checks of stored shapes)
		// allocation-free; a cached hash is consistent with equalsVoxelShape so it is also usable
		// as a fast-reject there. 0 is the uncomputed sentinel (recompute on the rare collision).
		if (value instanceof ShapeHashCache cacheable) {
			int cached = cacheable.ephemera$cachedShapeHash();
			if (cached != 0) {
				return cached;
			}
			int computed = computeShapeHash(value);
			cacheable.ephemera$setCachedShapeHash(computed);
			return computed;
		}
		return computeShapeHash(value);
	}

	private static int computeShapeHash(VoxelShape value) {
		int h = value.getClass().hashCode();
		h = 31 * h + value.toAabbs().hashCode();
		for (Axis axis : AXES) {
			h = 31 * h + hashDoubleList(value.getCoords(axis));
		}
		return h;
	}

	private static boolean equalsVoxelShape(VoxelShape a, VoxelShape b) {
		if (a.getClass() != b.getClass()) {
			return false;
		}
		if (a instanceof ShapeHashCache cacheA && b instanceof ShapeHashCache cacheB) {
			int hashA = cacheA.ephemera$cachedShapeHash();
			int hashB = cacheB.ephemera$cachedShapeHash();
			if (hashA != 0 && hashB != 0 && hashA != hashB) {
				return false;
			}
		}
		List<AABB> aBoxes = a.toAabbs();
		List<AABB> bBoxes = b.toAabbs();
		if (!aBoxes.equals(bBoxes)) {
			return false;
		}
		for (Axis axis : AXES) {
			if (!equalsDoubleList(a.getCoords(axis), b.getCoords(axis))) {
				return false;
			}
		}
		return true;
	}

	private static int hashDoubleList(DoubleList list) {
		int n = list.size();
		int h = n;
		for (int i = 0; i < n; i++) {
			long bits = Double.doubleToLongBits(list.getDouble(i));
			h = 31 * h + (int) (bits ^ (bits >>> 32));
		}
		return h;
	}

	private static boolean equalsDoubleList(DoubleList a, DoubleList b) {
		if (a == b) {
			return true;
		}
		int n = a.size();
		if (n != b.size()) {
			return false;
		}
		for (int i = 0; i < n; i++) {
			if (Double.doubleToLongBits(a.getDouble(i)) != Double.doubleToLongBits(b.getDouble(i))) {
				return false;
			}
		}
		return true;
	}

	private static int hashIdentityArray(Comparable<?>[] values) {
		int h = values.length;
		for (Comparable<?> v : values) {
			h = 31 * h + System.identityHashCode(v);
		}
		return h;
	}

	private static boolean equalsIdentityArray(Comparable<?>[] a, Comparable<?>[] b) {
		if (a.length != b.length) {
			return false;
		}
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}
}
