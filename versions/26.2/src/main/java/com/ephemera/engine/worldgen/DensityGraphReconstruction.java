package com.ephemera.engine.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import it.unimi.dsi.fastutil.doubles.DoubleList;

/**
 * Cached reflective constructors for the density-graph nodes whose classes are not referencable
 * from this package ({@code Mapped} and {@code ShiftedNoise} are protected nesteds of
 * {@code DensityFunctions}; {@code MulOrAdd}, {@code FindTopSurface} and {@code IntervalSelect}
 * are package-private with private constructors). Each handle replicates the exact vanilla
 * {@code mapChildren} reconstruction expression — see the disassembled bodies in the single-visit
 * identity mixins. A null handle means the reflection setup failed; callers then return without
 * injecting, which is plain vanilla single-visit behavior (the correct failure mode).
 *
 * <p>Handles are widened with {@code asType} to {@code Object} parameters (reference widening —
 * the inaccessible types upcast cleanly) so the call sites can use the signature-polymorphic
 * {@code invoke} with primitive {@code double}/{@code int} arguments: no per-call boxing and no
 * varargs {@code Object[]} allocation on the worldgen workers.
 */
public final class DensityGraphReconstruction {
	private static final Class<?> MAPPED = forName("net.minecraft.world.level.levelgen.DensityFunctions$Mapped");
	private static final Class<?> MAPPED_TYPE = forName("net.minecraft.world.level.levelgen.DensityFunctions$Mapped$Type");
	private static final Class<?> SHIFTED_NOISE = forName("net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise");
	private static final Class<?> MUL_OR_ADD = forName("net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd");
	private static final Class<?> MUL_OR_ADD_TYPE = forName("net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd$Type");
	private static final Class<?> FIND_TOP_SURFACE = forName("net.minecraft.world.level.levelgen.DensityFunctions$FindTopSurface");
	private static final Class<?> INTERVAL_SELECT = forName("net.minecraft.world.level.levelgen.DensityFunctions$IntervalSelect");

	/** Widens reference parameters/return to {@code Object} so {@code invoke} stays alloc-free. */
	private static MethodHandle widen(MethodHandle handle, Class<?> returnType, Class<?>... parameters) {
		if (handle == null) {
			return null;
		}
		try {
			return handle.asType(MethodType.methodType(returnType, parameters));
		} catch (Throwable t) {
			return null;
		}
	}

	/** {@code Mapped.create(Mapped$Type, DensityFunction)} — vanilla's own reconstruction factory. */
	private static final MethodHandle MAPPED_CREATE = widen(staticMethod(MAPPED, "create", MAPPED_TYPE, DensityFunction.class),
		DensityFunction.class, Object.class, DensityFunction.class);
	/** {@code Mapped.type} field getter (the field's type is not referencable here). */
	private static final MethodHandle MAPPED_TYPE_GETTER = widen(fieldGetter(MAPPED, "type"), Object.class);
	/** {@code new ShiftedNoise(DF, DF, DF, double, double, NoiseHolder)}. */
	private static final MethodHandle SHIFTED_NOISE_CTOR = widen(ctor(SHIFTED_NOISE,
		DensityFunction.class, DensityFunction.class, DensityFunction.class,
		double.class, double.class, DensityFunction.NoiseHolder.class),
		DensityFunction.class, Object.class, Object.class, Object.class, double.class, double.class, Object.class);
	/** {@code new MulOrAdd(MulOrAdd$Type, DF, double minValue, double maxValue, double argument)}. */
	private static final MethodHandle MUL_OR_ADD_CTOR = widen(ctor(MUL_OR_ADD,
		MUL_OR_ADD_TYPE, DensityFunction.class, double.class, double.class, double.class),
		DensityFunction.class, Object.class, Object.class, double.class, double.class, double.class);
	/** {@code MulOrAdd.specificType} field getter. */
	private static final MethodHandle MUL_OR_ADD_TYPE_GETTER = widen(fieldGetter(MUL_OR_ADD, "specificType"), Object.class);
	/** {@code new FindTopSurface(DF, DF, int lowerBound, int cellHeight)}. */
	private static final MethodHandle FIND_TOP_SURFACE_CTOR = widen(ctor(FIND_TOP_SURFACE,
		DensityFunction.class, DensityFunction.class, int.class, int.class),
		DensityFunction.class, Object.class, Object.class, int.class, int.class);
	/** {@code new IntervalSelect(DF, DoubleList, List<DF>)}. */
	private static final MethodHandle INTERVAL_SELECT_CTOR = widen(ctor(INTERVAL_SELECT,
		DensityFunction.class, DoubleList.class, List.class),
		DensityFunction.class, Object.class, Object.class, Object.class);

	private DensityGraphReconstruction() {}

	public static boolean mappedReady() {
		return MAPPED_CREATE != null && MAPPED_TYPE_GETTER != null;
	}

	public static boolean shiftedNoiseReady() {
		return SHIFTED_NOISE_CTOR != null;
	}

	public static boolean mulOrAddReady() {
		return MUL_OR_ADD_CTOR != null && MUL_OR_ADD_TYPE_GETTER != null;
	}

	public static boolean findTopSurfaceReady() {
		return FIND_TOP_SURFACE_CTOR != null;
	}

	public static boolean intervalSelectReady() {
		return INTERVAL_SELECT_CTOR != null;
	}

	/** {@code Mapped.create(node.type(), mappedInput)} for a reconstructed child. */
	public static DensityFunction mapped(Object originalNode, DensityFunction mappedInput) {
		try {
			Object type = (Object) MAPPED_TYPE_GETTER.invoke(originalNode);
			if (type == null) {
				return null;
			}
			return (DensityFunction) MAPPED_CREATE.invoke(type, mappedInput);
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static DensityFunction shiftedNoise(
			DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ,
			double xzScale, double yScale, DensityFunction.NoiseHolder noise) {
		try {
			return (DensityFunction) SHIFTED_NOISE_CTOR.invoke(shiftX, shiftY, shiftZ, xzScale, yScale, noise);
		} catch (Throwable ignored) {
			return null;
		}
	}

	/** {@code MulOrAdd(node.specificType, mappedInput, mappedInput.minValue(), maxValue, node.argument)}. */
	public static DensityFunction mulOrAdd(Object originalNode, DensityFunction mappedInput, double argument) {
		try {
			Object type = (Object) MUL_OR_ADD_TYPE_GETTER.invoke(originalNode);
			if (type == null) {
				return null;
			}
			return (DensityFunction) MUL_OR_ADD_CTOR.invoke(
				type, mappedInput, mappedInput.minValue(), mappedInput.maxValue(), argument);
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static DensityFunction findTopSurface(
			DensityFunction density, DensityFunction upperBound, int lowerBound, int cellHeight) {
		try {
			return (DensityFunction) FIND_TOP_SURFACE_CTOR.invoke(density, upperBound, lowerBound, cellHeight);
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static DensityFunction intervalSelect(
			DensityFunction input, DoubleList thresholds, List<DensityFunction> functions) {
		try {
			return (DensityFunction) INTERVAL_SELECT_CTOR.invoke(input, thresholds, functions);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Class<?> forName(String name) {
		try {
			return Class.forName(name);
		} catch (Throwable t) {
			return null;
		}
	}

	private static MethodHandle staticMethod(Class<?> owner, String name, Class<?>... parameters) {
		if (owner == null) {
			return null;
		}
		try {
			Method method = owner.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return MethodHandles.lookup().unreflect(method);
		} catch (Throwable t) {
			return null;
		}
	}

	private static MethodHandle ctor(Class<?> owner, Class<?>... parameters) {
		if (owner == null) {
			return null;
		}
		try {
			Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
			constructor.setAccessible(true);
			return MethodHandles.lookup().unreflectConstructor(constructor);
		} catch (Throwable t) {
			return null;
		}
	}

	private static MethodHandle fieldGetter(Class<?> owner, String name) {
		if (owner == null) {
			return null;
		}
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return MethodHandles.lookup().unreflectGetter(field);
		} catch (Throwable t) {
			return null;
		}
	}
}
