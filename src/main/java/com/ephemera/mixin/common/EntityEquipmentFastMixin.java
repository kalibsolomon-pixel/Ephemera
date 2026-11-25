package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import java.util.EnumMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks whether an {@link EntityEquipment} holds any non-empty {@link ItemStack} so empty
 * equipment instances (the vast majority of mobs) skip allocating an {@code EnumMap.entrySet()}
 * iterator on every single {@code LivingEntity.aiStep()} tick.
 */
@Mixin(EntityEquipment.class)
abstract class EntityEquipmentFastMixin {
	@Shadow @Final private EnumMap<EquipmentSlot, ItemStack> items;

	@Unique private boolean ephemera$maybeNonEmpty;

	@Inject(method = "<init>(Ljava/util/EnumMap;)V", at = @At("RETURN"))
	private void ephemera$onInitMap(EnumMap<EquipmentSlot, ItemStack> map, CallbackInfo ci) {
		for (ItemStack s : map.values()) {
			if (!s.isEmpty()) {
				this.ephemera$maybeNonEmpty = true;
				return;
			}
		}
	}

	@Inject(method = "set", at = @At("HEAD"))
	private void ephemera$onSet(EquipmentSlot slot, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
		if (!stack.isEmpty()) {
			this.ephemera$maybeNonEmpty = true;
		}
	}

	@Inject(method = "setAll", at = @At("HEAD"))
	private void ephemera$onSetAll(EntityEquipment other, CallbackInfo ci) {
		this.ephemera$maybeNonEmpty = true;
	}

	@Inject(method = "clear", at = @At("RETURN"))
	private void ephemera$onClear(CallbackInfo ci) {
		this.ephemera$maybeNonEmpty = false;
	}

	@Inject(method = "dropAll", at = @At("RETURN"))
	private void ephemera$onDropAll(CallbackInfo ci) {
		this.ephemera$maybeNonEmpty = false;
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastTick(Entity entity, CallbackInfo ci) {
		if (!EphemeraCommon.config().engineFastEntityLookup) {
			return;
		}
		if (!this.ephemera$maybeNonEmpty) {
			ci.cancel();
			return;
		}
		boolean any = false;
		for (var entry : this.items.entrySet()) {
			ItemStack stack = entry.getValue();
			if (!stack.isEmpty()) {
				any = true;
				stack.inventoryTick(entity.level(), entity, entry.getKey());
			}
		}
		this.ephemera$maybeNonEmpty = any;
		ci.cancel();
	}
}
