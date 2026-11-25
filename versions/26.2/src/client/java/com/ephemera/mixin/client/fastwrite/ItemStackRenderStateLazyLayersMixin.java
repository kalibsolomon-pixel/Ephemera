package com.ephemera.mixin.client.fastwrite;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState.LayerRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@link ItemStackRenderState} allocate its layer storage lazily. Vanilla's constructor
 * eagerly creates {@code new LayerRenderState[]{ new LayerRenderState(this) }}, and every
 * {@code LayerRenderState} eagerly allocates an {@code ArrayList} plus a {@code Matrix4f}
 * (~190 B per state). Humanoid/Avatar render states embed several of these, so the empty hand /
 * armor / head slots of a dense crowd alone cost hundreds of dead bytes per rendered entity per
 * frame before any item populates them.
 *
 * <p>Two hooks replace the eager init: the inner {@code new LayerRenderState(this)} resolves to
 * {@code null} (dropping the layer, its list, and its matrix) and the {@code layers} field store
 * resolves to a shared empty array (leaving the transient 1-element array for C2 scalar
 * replacement). {@code ensureCapacity} then grows from length zero and fills every reachable
 * slot with a fresh layer exactly as vanilla would. The only unguarded {@code layers[0]} reader
 * is {@code usesBlockLight()} via {@code firstLayer()}; a fresh vanilla layer's {@code
 * usesBlockLight} field defaults to {@code false}, so answering {@code false} for {@code
 * activeLayerCount == 0} is behavior-identical.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateLazyLayersMixin {
	@Unique
	private static final LayerRenderState[] EPHEMERA$EMPTY_LAYERS = new LayerRenderState[0];

	@Shadow
	private int activeLayerCount;

	@WrapOperation(
		method = "<init>",
		at = @At(
			value = "NEW",
			target = "(Lnet/minecraft/client/renderer/item/ItemStackRenderState;)Lnet/minecraft/client/renderer/item/ItemStackRenderState$LayerRenderState;"
		)
	)
	private LayerRenderState ephemera$noEagerLayer(ItemStackRenderState owner, Operation<LayerRenderState> original) {
		// The enclosing 1-element array is overwritten by the next hook before anything can read
		// the stored null, and never escapes the constructor.
		return null;
	}

	@WrapOperation(
		method = "<init>",
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;layers:[Lnet/minecraft/client/renderer/item/ItemStackRenderState$LayerRenderState;",
			opcode = Opcodes.PUTFIELD
		)
	)
	private void ephemera$storeEmptyLayers(
		ItemStackRenderState instance,
		LayerRenderState[] originalValue,
		Operation<Void> original
	) {
		original.call(instance, EPHEMERA$EMPTY_LAYERS);
	}

	@Inject(method = "usesBlockLight", at = @At("HEAD"), cancellable = true)
	private void ephemera$emptyStateUsesNoBlockLight(CallbackInfoReturnable<Boolean> cir) {
		if (this.activeLayerCount == 0) {
			cir.setReturnValue(false);
		}
	}
}
