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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates the per-entity {@code AABB.inflate(0.5)} and fallback/leash {@code new AABB}
 * allocations inside {@link EntityRenderer#shouldRender} (measured at ~150 MB / 25s on the
 * Render thread in JFR) by testing inflated primitive coordinates directly against
 * {@link FrustumFastCube}.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererFrustumFastMixin<T extends Entity> {
	@Shadow @Final protected EntityRenderDispatcher entityRenderDispatcher;

	@Shadow protected abstract boolean affectedByCulling(T entity);

	@Shadow protected abstract AABB getBoundingBoxForCulling(T entity, float partialTick);

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void ephemera$zeroAllocFrustumCull(
		T entity,
		Frustum frustum,
		double camX,
		double camY,
		double camZ,
		float partialTick,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!EphemeraCommon.config().terrainFastFrustumAndGrid || !(frustum instanceof FrustumFastCube fastFrustum)) {
			return;
		}
		if (!entity.shouldRender(camX, camY, camZ)) {
			cir.setReturnValue(false);
			return;
		}
		if (!this.affectedByCulling(entity)) {
			cir.setReturnValue(true);
			return;
		}
		AABB raw = this.getBoundingBoxForCulling(entity, partialTick);
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
			cir.setReturnValue(true);
			return;
		}
		if (entity instanceof Leashable leashable) {
			Entity holder = leashable.getLeashHolder();
			if (holder != null) {
				@SuppressWarnings("unchecked")
				EntityRenderer<Entity, ?> holderRenderer = (EntityRenderer<Entity, ?>) this.entityRenderDispatcher.getRenderer(holder);
				AABB hBox = ((EntityRendererAccessor) holderRenderer).ephemera$cullingBox(holder, partialTick);
				if (fastFrustum.ephemera$isVisible(hBox.minX, hBox.minY, hBox.minZ, hBox.maxX, hBox.maxY, hBox.maxZ)
					|| fastFrustum.ephemera$isVisible(
						Math.min(minX, hBox.minX), Math.min(minY, hBox.minY), Math.min(minZ, hBox.minZ),
						Math.max(maxX, hBox.maxX), Math.max(maxY, hBox.maxY), Math.max(maxZ, hBox.maxZ))) {
					cir.setReturnValue(true);
					return;
				}
			}
		}
		cir.setReturnValue(false);
	}
}
