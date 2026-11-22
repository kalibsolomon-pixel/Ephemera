package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Replaces the allocating {@code LongAVLTreeSet.subSet} + full-X-slice tree traversal in
 * {@link EntitySectionStorage#forEachAccessibleNonEmptySection} with direct zero-allocation
 * section-map probes for compact bounding boxes (&le; 64 sections, covering &gt;95% of entity
 * collision, AI, attack, projectile, and hopper queries).
 */
@Mixin(EntitySectionStorage.class)
abstract class EntitySectionStorageFastMixin<T extends EntityAccess> {
	@Unique
	private static final long EPHEMERA$DIRECT_PROBE_LIMIT = 64L;

	@Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;

	@WrapMethod(method = "forEachAccessibleNonEmptySection")
	private void ephemera$fastSectionQuery(
		AABB box,
		AbortableIterationConsumer<EntitySection<T>> consumer,
		Operation<Void> original
	) {
		if (!EphemeraCommon.config().engineFastEntityLookup) {
			original.call(box, consumer);
			return;
		}
		Long2ObjectMap<EntitySection<T>> map = this.sections;
		if (map.isEmpty()) {
			return;
		}
		int minX = SectionPos.posToSectionCoord(box.minX - 2.0);
		int minY = SectionPos.posToSectionCoord(box.minY - 4.0);
		int minZ = SectionPos.posToSectionCoord(box.minZ - 2.0);
		int maxX = SectionPos.posToSectionCoord(box.maxX + 2.0);
		int maxY = SectionPos.posToSectionCoord(box.maxY + 0.0);
		int maxZ = SectionPos.posToSectionCoord(box.maxZ + 2.0);

		long dx = (long) maxX - minX + 1L;
		long dy = (long) maxY - minY + 1L;
		long dz = (long) maxZ - minZ + 1L;
		if (dx <= 0L || dy <= 0L || dz <= 0L || dx > EPHEMERA$DIRECT_PROBE_LIMIT
			|| dy > EPHEMERA$DIRECT_PROBE_LIMIT || dz > EPHEMERA$DIRECT_PROBE_LIMIT
			|| dx * dy * dz > EPHEMERA$DIRECT_PROBE_LIMIT) {
			original.call(box, consumer);
			return;
		}

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = minY; y <= maxY; y++) {
					EntitySection<T> section = map.get(SectionPos.asLong(x, y, z));
					if (section != null && !section.isEmpty() && section.getStatus().isAccessible()) {
						if (consumer.accept(section).shouldAbort()) {
							return;
						}
					}
				}
			}
		}
	}
}
