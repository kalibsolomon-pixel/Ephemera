package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.culling.FastShadowCache;
import com.ephemera.render.culling.RendererTypeCache;
import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRendererCacheMixin {
    @Shadow private Map<EntityType<?>, EntityRenderer<?, ?>> renderers;
    @Unique private long ephemera$generation;
    @Redirect(method = {
            "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;"
    }, at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object ephemera$renderer(Map<?, ?> map, Object type) {
        if (!EphemeraCommon.config().entityRendererDirectCache || !(type instanceof RendererTypeCache cache)) return map.get(type);
        Object renderer = cache.ephemera$renderer(this, ephemera$generation);
        if (renderer == null) { renderer = map.get(type); cache.ephemera$renderer(this, ephemera$generation, renderer); }
        return renderer;
    }
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void ephemera$reload(CallbackInfo ci) {
        for (EntityType<?> type : renderers.keySet()) ((RendererTypeCache) type).ephemera$clearRenderer();
        ephemera$generation++; FastShadowCache.invalidate();
    }
}
