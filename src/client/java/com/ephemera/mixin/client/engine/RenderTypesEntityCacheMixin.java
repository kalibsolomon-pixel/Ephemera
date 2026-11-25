package com.ephemera.mixin.client.engine;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Avoids allocating a {@code com.mojang.datafixers.util.Pair<Identifier, Boolean>} on every mob
 * and player render call inside {@code RenderTypes.entityCutout}, {@code entityTranslucent}, and
 * {@code entityCutoutZOffset} (~45+ MB / 45s in crowded worlds and multiplayer lobbies). Cache
 * misses delegate directly to vanilla's memoized {@code BiFunction}. Stands down for Iris/Oculus.
 */
@Mixin(RenderTypes.class)
abstract class RenderTypesEntityCacheMixin {
    @Unique private static final int EPHEMERA_MAX_ENTRIES = 2048;
    @Unique private static final ConcurrentHashMap<Identifier, RenderType> EPHEMERA_CUTOUT_T = new ConcurrentHashMap<>(64);
    @Unique private static final ConcurrentHashMap<Identifier, RenderType> EPHEMERA_CUTOUT_F = new ConcurrentHashMap<>(64);
    @Unique private static final ConcurrentHashMap<Identifier, RenderType> EPHEMERA_TRANS_T = new ConcurrentHashMap<>(64);
    @Unique private static final ConcurrentHashMap<Identifier, RenderType> EPHEMERA_TRANS_F = new ConcurrentHashMap<>(64);
    @Unique private static final ConcurrentHashMap<Identifier, RenderType> EPHEMERA_CUTOUT_Z_T = new ConcurrentHashMap<>(32);
    @Unique private static final ConcurrentHashMap<Identifier, RenderType> EPHEMERA_CUTOUT_Z_F = new ConcurrentHashMap<>(32);

    @Shadow @Final private static BiFunction<Identifier, Boolean, RenderType> ENTITY_CUTOUT;
    @Shadow @Final private static BiFunction<Identifier, Boolean, RenderType> ENTITY_TRANSLUCENT;
    @Shadow @Final private static BiFunction<Identifier, Boolean, RenderType> ENTITY_CUTOUT_Z_OFFSET;

    @Unique
    private static RenderType ephemera$lookup(
            ConcurrentHashMap<Identifier, RenderType> cache,
            BiFunction<Identifier, Boolean, RenderType> fallback,
            Identifier texture,
            boolean outline) {
        RenderType rt = cache.get(texture);
        if (rt != null) {
            return rt;
        }
        rt = fallback.apply(texture, outline);
        if (rt != null && cache.size() < EPHEMERA_MAX_ENTRIES) {
            cache.put(texture, rt);
        }
        return rt;
    }

    /**
     * @author Ephemera
     * @reason Avoids per-call Pair.of(Identifier, Boolean) allocation in Util.memoize.
     */
    @Overwrite
    public static RenderType entityCutout(Identifier texture, boolean outline) {
        return ephemera$lookup(outline ? EPHEMERA_CUTOUT_T : EPHEMERA_CUTOUT_F, ENTITY_CUTOUT, texture, outline);
    }

    /**
     * @author Ephemera
     * @reason Avoids per-call Pair.of(Identifier, Boolean) allocation in Util.memoize.
     */
    @Overwrite
    public static RenderType entityTranslucent(Identifier texture, boolean outline) {
        return ephemera$lookup(outline ? EPHEMERA_TRANS_T : EPHEMERA_TRANS_F, ENTITY_TRANSLUCENT, texture, outline);
    }

    /**
     * @author Ephemera
     * @reason Avoids per-call Pair.of(Identifier, Boolean) allocation in Util.memoize.
     */
    @Overwrite
    public static RenderType entityCutoutZOffset(Identifier texture, boolean outline) {
        return ephemera$lookup(outline ? EPHEMERA_CUTOUT_Z_T : EPHEMERA_CUTOUT_Z_F, ENTITY_CUTOUT_Z_OFFSET, texture, outline);
    }
}
