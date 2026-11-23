package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.culling.FastShadowCache;
import com.ephemera.render.culling.RendererTypeCache;
import com.ephemera.render.gui.WorldPreparedTextCache;
import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Direct-field {@link EntityRenderer} lookup on {@link EntityType}, bypassing both vanilla's
 * {@code typeSwitch} / {@code Map.get} and any wrapper lambda indirection on the hot entity loop.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRendererCacheMixin {
    @Shadow private Map<EntityType<?>, EntityRenderer<?, ?>> renderers;
    @Unique private long ephemera$generation;

    @SuppressWarnings("unchecked")
    @Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("HEAD"), cancellable = true)
    private void ephemera$fastGetRenderer(Entity entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
        if (!EphemeraCommon.config().entityRendererDirectCache || entity == null || entity instanceof Avatar) {
            return;
        }
        EntityType<?> type = entity.getType();
        if (type instanceof RendererTypeCache cache) {
            Object renderer = cache.ephemera$renderer(this, ephemera$generation);
            if (renderer != null) {
                cir.setReturnValue((EntityRenderer<?, ?>) renderer);
            }
        }
    }

    @Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("RETURN"))
    private void ephemera$storeRenderer(Entity entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
        if (!EphemeraCommon.config().entityRendererDirectCache || entity == null || entity instanceof Avatar) {
            return;
        }
        EntityRenderer<?, ?> renderer = cir.getReturnValue();
        if (renderer != null && entity.getType() instanceof RendererTypeCache cache) {
            cache.ephemera$renderer(this, ephemera$generation, renderer);
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("HEAD"), cancellable = true)
    private void ephemera$fastGetStateRenderer(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
        if (!EphemeraCommon.config().entityRendererDirectCache || state == null || state instanceof AvatarRenderState) {
            return;
        }
        EntityType<?> type = state.entityType;
        if (type instanceof RendererTypeCache cache) {
            Object renderer = cache.ephemera$renderer(this, ephemera$generation);
            if (renderer != null) {
                cir.setReturnValue((EntityRenderer<?, ?>) renderer);
            }
        }
    }

    @Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("RETURN"))
    private void ephemera$storeStateRenderer(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
        if (!EphemeraCommon.config().entityRendererDirectCache || state == null || state instanceof AvatarRenderState) {
            return;
        }
        EntityRenderer<?, ?> renderer = cir.getReturnValue();
        if (renderer != null && state.entityType instanceof RendererTypeCache cache) {
            cache.ephemera$renderer(this, ephemera$generation, renderer);
        }
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void ephemera$reload(CallbackInfo ci) {
        for (EntityType<?> type : renderers.keySet()) ((RendererTypeCache) type).ephemera$clearRenderer();
        ephemera$generation++; FastShadowCache.invalidate(); WorldPreparedTextCache.invalidate();
    }
}
