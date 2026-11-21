package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.RendererTypeCache;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({EntityType.class, BlockEntityType.class})
public abstract class RendererTypeCacheMixin implements RendererTypeCache {
    @Unique private Object ephemera$dispatcher;
    @Unique private Object ephemera$cachedRenderer;
    @Unique private long ephemera$generation;
    @Override public Object ephemera$renderer(Object dispatcher, long generation) {
        return ephemera$dispatcher == dispatcher && ephemera$generation == generation ? ephemera$cachedRenderer : null;
    }
    @Override public void ephemera$renderer(Object dispatcher, long generation, Object renderer) {
        ephemera$dispatcher = dispatcher; ephemera$generation = generation; ephemera$cachedRenderer = renderer;
    }
    @Override public void ephemera$clearRenderer() { ephemera$dispatcher = null; ephemera$cachedRenderer = null; }
}
