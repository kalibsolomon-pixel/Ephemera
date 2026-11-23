package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.culling.EntityExtractPassCache;
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
 * Direct-field {@link EntityRenderer} lookup on {@link EntityType}, plus a pass-scoped single-entity
 * identity cache during {@code LevelExtractor.extractVisibleEntities} so each visible entity (including
 * players/Avatars and modded entities) resolves its renderer once instead of separately for visibility
 * and render-state extraction.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRendererCacheMixin implements EntityExtractPassCache {
    @Shadow private Map<EntityType<?>, EntityRenderer<?, ?>> renderers;
    @Unique private long ephemera$generation;
    @Unique private boolean ephemera$inExtractPass;
    @Unique private Entity ephemera$lastEntity;
    @Unique private EntityRenderer<?, ?> ephemera$lastRenderer;

    @Override
    public void ephemera$beginExtractPass() {
        ephemera$inExtractPass = true;
        ephemera$lastEntity = null;
        ephemera$lastRenderer = null;
    }

    @Override
    public void ephemera$endExtractPass() {
        ephemera$inExtractPass = false;
        ephemera$lastEntity = null;
        ephemera$lastRenderer = null;
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("HEAD"), cancellable = true)
    private void ephemera$fastGetRenderer(Entity entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
        if (!EphemeraCommon.config().entityRendererDirectCache || entity == null) {
            return;
        }
        if (entity instanceof Avatar) {
            if (ephemera$inExtractPass && entity == ephemera$lastEntity) {
                EntityRenderer<?, ?> cached = ephemera$lastRenderer;
                if (cached != null) {
                    cir.setReturnValue(cached);
                }
            }
            return;
        }
        EntityType<?> type = entity.getType();
        if (type instanceof RendererTypeCache cache) {
            Object renderer = cache.ephemera$renderer(this, ephemera$generation);
            if (renderer != null) {
                cir.setReturnValue((EntityRenderer<?, ?>) renderer);
                return;
            }
        }
        if (ephemera$inExtractPass && entity == ephemera$lastEntity) {
            EntityRenderer<?, ?> cached = ephemera$lastRenderer;
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        }
    }

    @Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("RETURN"))
    private void ephemera$storeRenderer(Entity entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
        if (!EphemeraCommon.config().entityRendererDirectCache || entity == null) {
            return;
        }
        EntityRenderer<?, ?> renderer = cir.getReturnValue();
        if (renderer == null) {
            return;
        }
        if (ephemera$inExtractPass) {
            ephemera$lastEntity = entity;
            ephemera$lastRenderer = renderer;
        }
        if (!(entity instanceof Avatar) && entity.getType() instanceof RendererTypeCache cache) {
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
        ephemera$endExtractPass();
        for (EntityType<?> type : renderers.keySet()) ((RendererTypeCache) type).ephemera$clearRenderer();
        ephemera$generation++; FastShadowCache.invalidate(); WorldPreparedTextCache.invalidate();
    }
}
