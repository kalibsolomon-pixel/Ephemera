package com.ephemera.engine.common;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Class-indexed, thread-safe method-override lookups for the collision fast paths. Vanilla block
 * classes are pre-populated from the registry at mod init so first-tick {@code checkInsideBlocks}
 * lookups never run the reflective hierarchy walk on a ticking thread; entity classes and
 * late/modded registrations populate lazily through the same table (no {@code ClassValue}
 * bootstrap lock, one shared table for the identical collide-override query).
 *
 * <p>Unlike {@code ClassValue}, entries hold strong {@code Class} keys. That is safe here because
 * Fabric/Knot never unloads classes for the lifetime of the process.
 */
public final class EntityClassIndex {
	private static final ConcurrentHashMap<Class<?>, Boolean> HAS_ENTITY_INSIDE = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Class<?>, Boolean> CAN_COLLIDE_OVERRIDE = new ConcurrentHashMap<>();

	private EntityClassIndex() {}

	/** Called once from mod init after vanilla registries bootstrap; lazy fills handle the rest. */
	public static void prepopulate() {
		try {
			for (Block block : BuiltInRegistries.BLOCK) {
				hasEntityInside(block.getClass());
			}
		} catch (Throwable ignored) {
			// Registries not iterable at this point on some loaders: lazy population still covers
			// every class on first use, just on the ticking thread like before.
		}
	}

	/** True when the hierarchy up to (excluding) {@link BlockBehaviour} declares {@code entityInside}. */
	public static boolean hasEntityInside(Class<?> type) {
		Boolean cached = HAS_ENTITY_INSIDE.get(type);
		if (cached != null) {
			return cached;
		}
		boolean value = walkHierarchy(type, BlockBehaviour.class, "entityInside");
		HAS_ENTITY_INSIDE.put(type, value);
		return value;
	}

	/** True when the hierarchy up to (excluding) {@link Entity} declares a collide override. */
	public static boolean canCollideOverride(Class<?> type) {
		Boolean cached = CAN_COLLIDE_OVERRIDE.get(type);
		if (cached != null) {
			return cached;
		}
		boolean value = walkHierarchy(type, Entity.class, "canCollideWith", "canBeCollidedWith");
		CAN_COLLIDE_OVERRIDE.put(type, value);
		return value;
	}

	private static boolean walkHierarchy(Class<?> type, Class<?> stop, String... names) {
		for (Class<?> c = type; c != null && c != stop; c = c.getSuperclass()) {
			for (var m : c.getDeclaredMethods()) {
				for (String name : names) {
					if (name.equals(m.getName())) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
