package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.terrain.FrustumFastCube;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Eliminates per-entity {@code AABB.inflate(0.5)}, fallback/leash {@code new AABB},
 * {@code CallbackInfoReturnable}, and megamorphic {@code @WrapMethod} lambda allocations (~71 MB)
 * inside {@link EntityRenderer#shouldRender} by testing inflated primitive coordinates directly
 * against {@link FrustumFastCube}. Stands down for Sodium, EntityCulling, and BadOptimizations.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererFrustumFastMixin<T extends Entity> {
	@Shadow @Final protected EntityRenderDispatcher entityRenderDispatcher;

	@Shadow protected abstract boolean affectedByCulling(T entity);

	@Shadow protected abstract AABB getBoundingBoxForCulling(T entity);

	/**
	 * @author Ephemera
	 * @reason Zero-allocation primitive frustum test; avoids AABB.inflate, CIR, and megamorphic lambda allocations.
	 */
	@Overwrite
	public boolean shouldRender(T entity, Frustum frustum, double camX, double camY, double camZ) {
		if (!entity.shouldRender(camX, camY, camZ)) {
			return false;
		}
		if (!this.affectedByCulling(entity)) {
			return true;
		}
		if (EphemeraCommon.config().terrainFastFrustumAndGrid && frustum instanceof FrustumFastCube fastFrustum) {
			AABB raw = this.getBoundingBoxForCulling(entity);
			double minX = raw.minX - 0.5;
			double minY = raw.minY - 0.5;
			double minZ = raw.minZ - 0.5;
			double maxX = raw.maxX + 0.5;
			double maxY = raw.maxY + 0.5;
			double maxZ = raw.maxZ + 0.5;
			double sizeSum = (maxX - minX) + (maxY - minY) + (maxZ - minZ);
			if (Double.isNaN(minX) || Double.isNaN(minY) || Double.isNaN(minZ)
				|| Double.isNaN(maxX) || Double.isNaN(maxY) || Double.isNaN(maxZ)
				|| sizeSum / 3.0 == 0.0) {
				double ex = entity.getX();
				double ey = entity.getY();
				double ez = entity.getZ();
				minX = ex - 2.0;
				minY = ey - 2.0;
				minZ = ez - 2.0;
				maxX = ex + 2.0;
				maxY = ey + 2.0;
				maxZ = ez + 2.0;
			}
			if (fastFrustum.ephemera$isVisible(minX, minY, minZ, maxX, maxY, maxZ)) {
				return true;
			}
			if (entity instanceof Leashable leashable && leashable.getLeashData() != null) {
				Entity holder = leashable.getLeashHolder();
				if (holder != null) {
					@SuppressWarnings("unchecked")
					EntityRenderer<Entity, ?> holderRenderer = (EntityRenderer<Entity, ?>) this.entityRenderDispatcher.getRenderer(holder);
					AABB hBox = ((EntityRendererAccessor) holderRenderer).ephemera$cullingBox(holder);
					if (fastFrustum.ephemera$isVisible(hBox.minX, hBox.minY, hBox.minZ, hBox.maxX, hBox.maxY, hBox.maxZ)
						|| fastFrustum.ephemera$isVisible(
							Math.min(minX, hBox.minX), Math.min(minY, hBox.minY), Math.min(minZ, hBox.minZ),
							Math.max(maxX, hBox.maxX), Math.max(maxY, hBox.maxY), Math.max(maxZ, hBox.maxZ))) {
						return true;
					}
				}
			}
			return false;
		}
		// Exact vanilla fallback when disabled or non-standard Frustum subclass.
		AABB box = this.getBoundingBoxForCulling(entity).inflate(0.5);
		if (box.hasNaN() || box.getSize() == 0.0) {
			box = new AABB(entity.getX() - 2.0, entity.getY() - 2.0, entity.getZ() - 2.0,
				entity.getX() + 2.0, entity.getY() + 2.0, entity.getZ() + 2.0);
		}
		if (frustum.isVisible(box)) {
			return true;
		}
		if (entity instanceof Leashable leashable && leashable.getLeashData() != null) {
			Entity holder = leashable.getLeashHolder();
			if (holder != null) {
				@SuppressWarnings("unchecked")
				EntityRenderer<Entity, ?> holderRenderer = (EntityRenderer<Entity, ?>) this.entityRenderDispatcher.getRenderer(holder);
				AABB hBox = ((EntityRendererAccessor) holderRenderer).ephemera$cullingBox(holder);
				return frustum.isVisible(hBox) || frustum.isVisible(box.minmax(hBox));
			}
		}
		return false;
	}
}
