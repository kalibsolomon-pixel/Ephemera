package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.terrain.FrustumFastCube;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Eliminates the per-entity {@code Entity.getInterpolatedBoundingBox} ({@code AABB.move}),
 * {@code AABB.inflate(0.5)}, fallback/leash {@code new AABB}, and {@code CallbackInfoReturnable}
 * allocations inside 26.3 {@link EntityRenderer#shouldRender} by computing interpolated bounds in
 * primitive doubles and testing them directly against {@link FrustumFastCube}.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererFrustumFastMixin<T extends Entity> {
	@Unique private static final byte EPHEMERA$BOX_STANDARD = 0;
	@Unique private static final byte EPHEMERA$BOX_LIVING = 1;
	@Unique private static final byte EPHEMERA$BOX_CUSTOM = 2;

	@Unique
	private static final ClassValue<Byte> EPHEMERA$CULL_BOX_KIND = new ClassValue<>() {
		@Override
		protected Byte computeValue(Class<?> type) {
			boolean sawLiving = false;
			for (Class<?> c = type; c != null && c != EntityRenderer.class; c = c.getSuperclass()) {
				for (Method m : c.getDeclaredMethods()) {
					if (m.isSynthetic() || m.isBridge()) {
						continue;
					}
					if ("getBoundingBoxForCulling".equals(m.getName()) && m.getParameterCount() == 2) {
						Class<?>[] params = m.getParameterTypes();
						if (params[1] == float.class) {
							if (c == LivingEntityRenderer.class) {
								sawLiving = true;
							} else {
								return EPHEMERA$BOX_CUSTOM;
							}
						}
					}
				}
			}
			return sawLiving ? EPHEMERA$BOX_LIVING : EPHEMERA$BOX_STANDARD;
		}
	};

	@Shadow @Final protected EntityRenderDispatcher entityRenderDispatcher;

	@Shadow protected abstract boolean affectedByCulling(T entity);

	@Shadow protected abstract AABB getBoundingBoxForCulling(T entity, float partialTick);

	/**
	 * @author Ephemera
	 * @reason Zero-allocation interpolated primitive AABB frustum cull without CallbackInfoReturnable boxing.
	 */
	@Overwrite
	public boolean shouldRender(
		T entity,
		Frustum frustum,
		double camX,
		double camY,
		double camZ,
		float partialTick
	) {
		if (!entity.shouldRender(camX, camY, camZ)) {
			return false;
		}
		if (!this.affectedByCulling(entity)) {
			return true;
		}
		if (!EphemeraCommon.config().terrainFastFrustumAndGrid || !(frustum instanceof FrustumFastCube fastFrustum)) {
			AABB box = this.getBoundingBoxForCulling(entity, partialTick).inflate(0.5);
			if (box.hasNaN() || box.getSize() == 0.0) {
				box = new AABB(entity.getX() - 2.0, entity.getY() - 2.0, entity.getZ() - 2.0,
					entity.getX() + 2.0, entity.getY() + 2.0, entity.getZ() + 2.0);
			}
			if (frustum.isVisible(box)) {
				return true;
			}
			if (entity instanceof Leashable leashable) {
				Entity holder = leashable.getLeashHolder();
				if (holder != null) {
					@SuppressWarnings("unchecked")
					EntityRenderer<Entity, ?> holderRenderer = (EntityRenderer<Entity, ?>) this.entityRenderDispatcher.getRenderer(holder);
					AABB hBox = ((EntityRendererAccessor) holderRenderer).ephemera$cullingBox(holder, partialTick);
					return frustum.isVisible(hBox) || frustum.isVisible(box.minmax(hBox));
				}
			}
			return false;
		}

		byte kind = EPHEMERA$CULL_BOX_KIND.get(this.getClass());
		double minX, minY, minZ, maxX, maxY, maxZ;
		if (kind != EPHEMERA$BOX_CUSTOM) {
			AABB raw = entity.getBoundingBox();
			double inflate = 0.5;
			if (kind == EPHEMERA$BOX_LIVING && entity instanceof LivingEntity living
				&& living.getItemBySlot(EquipmentSlot.HEAD).is(Items.DRAGON_HEAD)) {
				inflate = 1.0;
			}
			double ex = entity.getX();
			double ey = entity.getY();
			double ez = entity.getZ();
			if (entity.xOld == ex && entity.yOld == ey && entity.zOld == ez) {
				minX = raw.minX - inflate;
				minY = raw.minY - inflate;
				minZ = raw.minZ - inflate;
				maxX = raw.maxX + inflate;
				maxY = raw.maxY + inflate;
				maxZ = raw.maxZ + inflate;
			} else {
				double pt = partialTick;
				double dx = Mth.lerp(pt, entity.xOld, ex) - ex;
				double dy = Mth.lerp(pt, entity.yOld, ey) - ey;
				double dz = Mth.lerp(pt, entity.zOld, ez) - ez;
				minX = raw.minX + dx - inflate;
				minY = raw.minY + dy - inflate;
				minZ = raw.minZ + dz - inflate;
				maxX = raw.maxX + dx + inflate;
				maxY = raw.maxY + dy + inflate;
				maxZ = raw.maxZ + dz + inflate;
			}
		} else {
			AABB raw = this.getBoundingBoxForCulling(entity, partialTick);
			minX = raw.minX - 0.5;
			minY = raw.minY - 0.5;
			minZ = raw.minZ - 0.5;
			maxX = raw.maxX + 0.5;
			maxY = raw.maxY + 0.5;
			maxZ = raw.maxZ + 0.5;
		}
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
					return true;
				}
			}
		}
		return false;
	}
}
