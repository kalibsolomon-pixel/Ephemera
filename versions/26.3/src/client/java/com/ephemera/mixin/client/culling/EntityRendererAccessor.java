package com.ephemera.mixin.client.culling;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {
    @Invoker("getBoundingBoxForCulling") AABB ephemera$cullingBox(Entity entity, float partialTick);
    @Accessor("shadowRadius") float ephemera$shadowRadius();
}
