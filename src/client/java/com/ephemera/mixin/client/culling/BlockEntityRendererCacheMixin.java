package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.culling.RendererTypeCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRendererCacheMixin {
    @Shadow private Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> renderers;
    @Unique private long ephemera$generation;
    @WrapOperation(method = {
            "getRenderer(Lnet/minecraft/world/level/block/entity/BlockEntity;)Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;",
            "getRenderer(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;)Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;"
    }, at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object ephemera$renderer(Map<?, ?> map, Object type, Operation<Object> original) {
        if (!EphemeraCommon.config().blockEntityRendererDirectCache || !(type instanceof RendererTypeCache cache)) return original.call(map, type);
        Object renderer = cache.ephemera$renderer(this, ephemera$generation);
        if (renderer == null) { renderer = original.call(map, type); cache.ephemera$renderer(this, ephemera$generation, renderer); }
        return renderer;
    }
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void ephemera$reload(CallbackInfo ci) {
        for (BlockEntityType<?> type : renderers.keySet()) ((RendererTypeCache) type).ephemera$clearRenderer();
        ephemera$generation++;
    }
}
