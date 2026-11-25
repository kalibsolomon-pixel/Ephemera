package com.ephemera.mixin.client.item;

import com.ephemera.render.fastwrite.CachedItemQuadList;
import com.ephemera.render.item.LayerSharedQuadsAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shares an immutable {@link CachedItemQuadList} directly from 26.2 {@link CuboidItemModelWrapper}
 * into {@link ItemStackRenderState.LayerRenderState}, eliminating per-item per-frame
 * {@code ArrayList.addAll} copies (matching Mojang's 26.3 {@code ItemQuads} design).
 */
@Mixin(CuboidItemModelWrapper.class)
public abstract class CuboidItemModelSharedQuadsMixin {
	@Shadow @Final private QuadCollection quads;

	@Unique private @Nullable CachedItemQuadList ephemera$cachedQuads;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$precomputeSharedQuads(CallbackInfo ci) {
		List<BakedQuad> all = this.quads.getAll();
		boolean translucent = false;
		for (int i = 0, n = all.size(); i < n; i++) {
			if (all.get(i).materialInfo().itemRenderType().hasBlending()) {
				translucent = true;
				break;
			}
		}
		this.ephemera$cachedQuads = CachedItemQuadList.of(all, translucent);
	}

	@WrapOperation(
		method = "update",
		at = @At(value = "INVOKE", target = "Ljava/util/List;addAll(Ljava/util/Collection;)Z")
	)
	private boolean ephemera$shareImmutableQuads(
		List<BakedQuad> targetList,
		Collection<? extends BakedQuad> src,
		Operation<Boolean> original,
		@Local ItemStackRenderState.LayerRenderState layer
	) {
		CachedItemQuadList cached = this.ephemera$cachedQuads;
		if (cached != null && (Object) layer instanceof LayerSharedQuadsAccess access && targetList.isEmpty()) {
			access.ephemera$setSharedQuads(cached);
			return !cached.isEmpty();
		}
		return original.call(targetList, src);
	}
}
