package com.ephemera.mixin.client.item;

import com.ephemera.render.fastwrite.CachedItemQuadList;
import com.ephemera.render.item.LayerSharedQuadsAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Holds a zero-copy {@link CachedItemQuadList} reference on 26.2
 * {@link ItemStackRenderState.LayerRenderState}. Any caller that later invokes
 * {@code prepareQuadList()} to mutate the list triggers transparent copy-on-write materialization.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public abstract class LayerRenderStateSharedQuadsMixin implements LayerSharedQuadsAccess {
	@Shadow @Final private List<BakedQuad> quads;

	@Unique private @Nullable CachedItemQuadList ephemera$sharedQuads;

	@Override
	public void ephemera$setSharedQuads(CachedItemQuadList quads) {
		this.ephemera$sharedQuads = quads;
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void ephemera$clearSharedQuads(CallbackInfo ci) {
		this.ephemera$sharedQuads = null;
	}

	@Inject(method = "prepareQuadList", at = @At("HEAD"))
	private void ephemera$materializeOnMutate(CallbackInfoReturnable<List<BakedQuad>> cir) {
		CachedItemQuadList shared = this.ephemera$sharedQuads;
		if (shared != null) {
			this.ephemera$sharedQuads = null;
			if (!shared.isEmpty()) {
				this.quads.addAll(shared);
			}
		}
	}

	@WrapOperation(
		method = "submit",
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState$LayerRenderState;quads:Ljava/util/List;",
			opcode = Opcodes.GETFIELD
		)
	)
	private List<BakedQuad> ephemera$submitSharedQuads(
		ItemStackRenderState.LayerRenderState self,
		Operation<List<BakedQuad>> original
	) {
		CachedItemQuadList shared = this.ephemera$sharedQuads;
		return shared != null ? shared : original.call(self);
	}
}
