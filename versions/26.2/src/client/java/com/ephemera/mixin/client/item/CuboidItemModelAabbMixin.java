package com.ephemera.mixin.client.item;

import com.ephemera.render.item.CuboidAabbCacheOwner;
import com.ephemera.render.item.ItemStackAabbAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caches the immutable per-{@link ItemDisplayContext} bounding box of a
 * {@link CuboidItemModelWrapper} so repeated dropped/held item extractions reuse the exact
 * {@link AABB} computed by vanilla without re-running {@code visitExtents()}.
 */
@Mixin(CuboidItemModelWrapper.class)
public abstract class CuboidItemModelAabbMixin implements CuboidAabbCacheOwner {
	@Unique private static final int EPHEMERA$DISPLAY_CONTEXT_COUNT = ItemDisplayContext.values().length;
	@Unique private @Nullable AABB @Nullable [] ephemera$cachedAabbs;

	@Override
	public @Nullable AABB ephemera$getCachedAabb(ItemDisplayContext context) {
		AABB[] arr = this.ephemera$cachedAabbs;
		return arr != null ? arr[context.ordinal()] : null;
	}

	@Override
	public void ephemera$putCachedAabb(ItemDisplayContext context, AABB aabb) {
		AABB[] arr = this.ephemera$cachedAabbs;
		if (arr == null) {
			arr = new AABB[EPHEMERA$DISPLAY_CONTEXT_COUNT];
			this.ephemera$cachedAabbs = arr;
		}
		arr[context.ordinal()] = aabb;
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void ephemera$stampCachedAabb(
		ItemStackRenderState state,
		ItemStack stack,
		ItemModelResolver resolver,
		ItemDisplayContext displayContext,
		ClientLevel level,
		ItemOwner owner,
		int seed,
		CallbackInfo ci
	) {
		if (displayContext != null && (Object) state instanceof ItemStackAabbAccess access) {
			access.ephemera$attachCuboidAabb(this, displayContext, ephemera$getCachedAabb(displayContext));
		}
	}
}
