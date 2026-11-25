package com.ephemera.mixin.client.item;

import com.ephemera.render.item.CuboidAabbCacheOwner;
import com.ephemera.render.item.ItemStackAabbAccess;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Allows single-layer {@code CuboidItemModelWrapper} items to stamp and populate their immutable
 * per-{@link ItemDisplayContext} model {@link AABB}, eliminating per-frame {@code visitExtents}
 * matrix transforms and {@code Vector3f}/{@code Pose}/{@code AABB.Builder} allocations on every
 * dropped item entity. Multi-layer items invalidate immediately when a second layer is added.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateAabbMixin implements ItemStackAabbAccess {
	@Shadow private int activeLayerCount;
	@Shadow private @Nullable AABB cachedModelBoundingBox;

	@Unique private @Nullable CuboidAabbCacheOwner ephemera$aabbOwner;
	@Unique private @Nullable ItemDisplayContext ephemera$aabbContext;

	@Inject(method = "clear", at = @At("HEAD"))
	private void ephemera$clearAabbOwner(CallbackInfo ci) {
		this.ephemera$aabbOwner = null;
		this.ephemera$aabbContext = null;
	}

	@Inject(method = "newLayer", at = @At("HEAD"))
	private void ephemera$invalidateOnMultiLayer(CallbackInfoReturnable<ItemStackRenderState.LayerRenderState> cir) {
		if (this.activeLayerCount > 0) {
			this.ephemera$aabbOwner = null;
			this.ephemera$aabbContext = null;
			this.cachedModelBoundingBox = null;
		}
	}

	@Override
	public void ephemera$attachCuboidAabb(CuboidAabbCacheOwner owner, ItemDisplayContext context, @Nullable AABB cached) {
		if (this.activeLayerCount != 1) {
			this.ephemera$aabbOwner = null;
			this.ephemera$aabbContext = null;
			return;
		}
		if (cached != null) {
			this.cachedModelBoundingBox = cached;
			this.ephemera$aabbOwner = null;
			this.ephemera$aabbContext = null;
		} else {
			this.ephemera$aabbOwner = owner;
			this.ephemera$aabbContext = context;
		}
	}

	@Inject(method = "getModelBoundingBox", at = @At("RETURN"))
	private void ephemera$storeComputedAabb(CallbackInfoReturnable<AABB> cir) {
		CuboidAabbCacheOwner owner = this.ephemera$aabbOwner;
		if (owner != null) {
			ItemDisplayContext ctx = this.ephemera$aabbContext;
			this.ephemera$aabbOwner = null;
			this.ephemera$aabbContext = null;
			if (this.activeLayerCount == 1 && ctx != null) {
				AABB computed = cir.getReturnValue();
				if (computed != null) {
					owner.ephemera$putCachedAabb(ctx, computed);
				}
			}
		}
	}
}
