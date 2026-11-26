package com.ephemera.mixin.client.gui;

import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Empty-map short-circuit for vanilla {@code getCooldownPercent}: vanilla unconditionally reads
 * the stack's cooldown group (a data-component map get plus a registry-key lookup) and probes the
 * cooldown map even when no cooldown has ever been recorded — the HUD pays that per item, every
 * frame, for a map that is empty in the overwhelming majority of sessions. Returning 0.0F on an
 * empty map is exact: with no entries there is no cooldown by construction, and the map refills
 * through {@code addCooldown} the moment any cooldown starts.
 */
@Mixin(ItemCooldowns.class)
public abstract class ItemCooldownsEmptyGuardMixin {
	// CooldownInstance is a private nested type; the erased descriptor (Map) matches either way.
	@Shadow private Map<Identifier, Object> cooldowns;

	@Inject(method = "getCooldownPercent", at = @At("HEAD"), cancellable = true)
	private void ephemera$emptyMapGuard(ItemStack stack, float delta, CallbackInfoReturnable<Float> cir) {
		if (this.cooldowns.isEmpty()) {
			cir.setReturnValue(0.0F);
		}
	}
}
