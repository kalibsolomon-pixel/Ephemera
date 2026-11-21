package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiFeatures;
import com.ephemera.render.gui.GuiItemStateCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsExtractorMixin {
	private static final String ITEM = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V";

	@Shadow @Final private Matrix3x2fStack pose;
	@Shadow @Final public GuiGraphicsExtractor.ScissorStack scissorStack;
	@Shadow @Final public GuiRenderState guiRenderState;

	@Inject(method = ITEM, at = @At("HEAD"), cancellable = true)
	private void ephemera$reuseResolvedItem(LivingEntity owner, Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
		if (!GuiFeatures.get().itemStateCache || stack.isEmpty()) {
			return;
		}
		TrackingItemStackRenderState state = GuiItemStateCache.find(stack, level, owner, seed);
		if (state != null) {
			guiRenderState.addItem(new GuiItemRenderState(new Matrix3x2f(pose), state, x, y, scissorStack.peek()));
			ci.cancel();
		}
	}

	@WrapOperation(
		method = ITEM,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemModelResolver;updateForTopItem(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V")
	)
	private void ephemera$rememberResolvedItem(
		ItemModelResolver resolver, ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
		Level level, ItemOwner owner, int seed, Operation<Void> original
	) {
		original.call(resolver, state, stack, context, level, owner, seed);
		if (GuiFeatures.get().itemStateCache && state instanceof TrackingItemStackRenderState tracking
			&& (owner == null || owner instanceof LivingEntity)) {
			GuiItemStateCache.store(stack, level, (LivingEntity) owner, seed, tracking);
		}
	}
}
