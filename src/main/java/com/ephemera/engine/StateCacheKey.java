package com.ephemera.engine;

import net.minecraft.world.phys.shapes.VoxelShape;

/** Every value stored by the immutable 26.2 BlockStateBase.Cache. */
public record StateCacheKey(VoxelShape collision, boolean[] sturdy, boolean large, boolean full) {
    @Override public boolean equals(Object other) {
        return other instanceof StateCacheKey key && collision == key.collision && sturdy == key.sturdy
                && large == key.large && full == key.full;
    }

    @Override public int hashCode() {
        int hash = 31 * System.identityHashCode(collision) + System.identityHashCode(sturdy);
        return 31 * hash + (large ? 2 : 0) + (full ? 1 : 0);
    }
}
