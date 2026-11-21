package com.ephemera.engine;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;

/** Bounded pools only see completed, immutable state data during cache construction. */
public final class StateInterning {
    private static final int MASK = 2047;
    private static final VoxelShape[] SHAPES = new VoxelShape[MASK + 1];
    private static final VoxelShape[][] FACES = new VoxelShape[MASK + 1][];
    private static final boolean[][] SUPPORTS = new boolean[MASK + 1][];
    private static final StateCacheView[] CACHES = new StateCacheView[MASK + 1];
    private static final Axis[] AXES = Axis.values();

    private StateInterning() {}

    public static synchronized boolean[] supports(boolean[] values) {
        int index = Arrays.hashCode(values) & MASK;
        if (Arrays.equals(values, SUPPORTS[index])) return SUPPORTS[index];
        return SUPPORTS[index] = values;
    }

    public static synchronized VoxelShape[] faces(VoxelShape[] values) {
        int hash = 1;
        for (VoxelShape face : values) hash = 31 * hash + System.identityHashCode(face);
        int index = hash & MASK;
        VoxelShape[] previous = FACES[index];
        if (previous != null && previous.length == values.length) {
            int i = 0;
            while (i < values.length && values[i] == previous[i]) i++;
            if (i == values.length) return previous;
        }
        return FACES[index] = values;
    }

    public static synchronized Object cache(Object cache) {
        if (!(cache instanceof StateCacheView view)) return cache;
        StateCacheKey key = view.ephemera$key();
        int index = key.hashCode() & MASK;
        StateCacheView other = CACHES[index];
        if (other != null && key.equals(other.ephemera$key())) return other;
        return CACHES[index] = view;
    }

    public static synchronized VoxelShape shape(VoxelShape value) {
        if (value == Shapes.empty() || value == Shapes.block()) return value;
        Class<?> type = value.getClass();
        if (type != ArrayVoxelShape.class && type != CubeVoxelShape.class && type != SliceShape.class) return value;
        List<AABB> boxes = value.toAabbs();
        int index = boxes.hashCode() & MASK;
        VoxelShape other = SHAPES[index];
        if (value == other) return value;
        if (other != null && type == other.getClass() && boxes.equals(other.toAabbs())) {
            for (Axis axis : AXES) {
                if (!value.getCoords(axis).equals(other.getCoords(axis))) return SHAPES[index] = value;
            }
            return other;
        }
        return SHAPES[index] = value;
    }
}
