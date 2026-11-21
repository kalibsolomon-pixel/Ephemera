package com.ephemera.mixin.client.visuals;

import com.ephemera.visuals.DynamicLightTracker;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every entity, held item, and first-person hand resolves its light through this method
 * (EntityRenderDispatcher delegates here), so one hook covers all of them.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererLightMixin {
	@Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
	private void ephemera$dynamicLight(Entity entity, float partialTick, CallbackInfoReturnable<Integer> cir) {
		int base = cir.getReturnValueI();
		int boosted = DynamicLightTracker.apply(entity, base);
		if (boosted != base) {
			cir.setReturnValue(boosted);
		}
	}
}
