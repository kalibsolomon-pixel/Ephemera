package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.culling.EntityExtractPassCache;
import com.ephemera.render.culling.FastShadowCache;
import com.ephemera.render.culling.RendererTypeCache;
import com.ephemera.render.gui.WorldPreparedTextCache;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zero-allocation direct-field {@link EntityRenderer} lookup on {@link EntityType} and direct
 * {@code WIDE}/{@code SLIM} {@link AvatarRenderer} fields for crowded multiplayer lobbies, plus a
 * pass-scoped single-entity identity cache during {@code LevelExtractor.extractVisibleEntities}.
 * Uses {@code @Overwrite} to eliminate both {@code CallbackInfoReturnable} (~110 MB) and
 * {@code @WrapMethod} {@code Operation} lambda (~122 MB) allocations. Stands down for BadOptimizations.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRendererCacheMixin implements EntityExtractPassCache {
    @Shadow private Map<EntityType<?>, EntityRenderer<?, ?>> renderers;
    @Shadow private Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> playerRenderers;
    @Shadow private Map<PlayerModelType, AvatarRenderer<ClientMannequin>> mannequinRenderers;

    @Shadow
    protected abstract <T extends Avatar & ClientAvatarEntity> AvatarRenderer<T> getAvatarRenderer(
            Map<PlayerModelType, AvatarRenderer<T>> renderers, T avatar);

    @Unique private long ephemera$generation;
    @Unique private boolean ephemera$inExtractPass;
    @Unique private Entity ephemera$lastEntity;
    @Unique private EntityRenderer<?, ?> ephemera$lastRenderer;
    @Unique private AvatarRenderer<AbstractClientPlayer> ephemera$widePlayerRenderer;
    @Unique private AvatarRenderer<AbstractClientPlayer> ephemera$slimPlayerRenderer;
    @Unique private AvatarRenderer<ClientMannequin> ephemera$wideMannequinRenderer;
    @Unique private AvatarRenderer<ClientMannequin> ephemera$slimMannequinRenderer;
    @Unique private boolean ephemera$avatarCachePopulated;

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

    @Unique
    private void ephemera$ensureAvatarCache() {
        if (ephemera$avatarCachePopulated) {
            return;
        }
        Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> players = this.playerRenderers;
        if (players != null && !players.isEmpty()) {
            ephemera$widePlayerRenderer = players.get(PlayerModelType.WIDE);
            ephemera$slimPlayerRenderer = players.get(PlayerModelType.SLIM);
            if (ephemera$slimPlayerRenderer == null) {
                ephemera$slimPlayerRenderer = ephemera$widePlayerRenderer;
            }
            Map<PlayerModelType, AvatarRenderer<ClientMannequin>> mannequins = this.mannequinRenderers;
            if (mannequins != null && !mannequins.isEmpty()) {
                ephemera$wideMannequinRenderer = mannequins.get(PlayerModelType.WIDE);
                ephemera$slimMannequinRenderer = mannequins.get(PlayerModelType.SLIM);
                if (ephemera$slimMannequinRenderer == null) {
                    ephemera$slimMannequinRenderer = ephemera$wideMannequinRenderer;
                }
            }
            ephemera$avatarCachePopulated = ephemera$widePlayerRenderer != null;
        }
    }

    /**
     * @author Ephemera
     * @reason Zero-allocation direct EntityType &amp; WIDE/SLIM player renderer lookup (eliminates 122 MB Operation lambdas).
     */
    @SuppressWarnings("unchecked")
    @Overwrite
    public <T extends Entity> EntityRenderer<? super T, ?> getRenderer(T entity) {
        Objects.requireNonNull(entity);
        boolean fast = EphemeraCommon.config().entityRendererDirectCache;
        if (entity instanceof AbstractClientPlayer player) {
            if (fast) {
                if (!ephemera$avatarCachePopulated) {
                    ephemera$ensureAvatarCache();
                }
                AvatarRenderer<AbstractClientPlayer> r = player.getSkin().model() == PlayerModelType.SLIM
                        ? ephemera$slimPlayerRenderer
                        : ephemera$widePlayerRenderer;
                if (r != null) {
                    return (EntityRenderer<? super T, ?>) r;
                }
            }
            return (EntityRenderer<? super T, ?>) this.getAvatarRenderer(this.playerRenderers, player);
        }
        if (entity instanceof ClientMannequin mannequin) {
            if (fast) {
                if (!ephemera$avatarCachePopulated) {
                    ephemera$ensureAvatarCache();
                }
                AvatarRenderer<ClientMannequin> r = mannequin.getSkin().model() == PlayerModelType.SLIM
                        ? ephemera$slimMannequinRenderer
                        : ephemera$wideMannequinRenderer;
                if (r != null) {
                    return (EntityRenderer<? super T, ?>) r;
                }
            }
            return (EntityRenderer<? super T, ?>) this.getAvatarRenderer(this.mannequinRenderers, mannequin);
        }
        EntityType<?> type = entity.getType();
        if (fast) {
            if (type instanceof RendererTypeCache cache) {
                Object renderer = cache.ephemera$renderer(this, ephemera$generation);
                if (renderer != null) {
                    return (EntityRenderer<? super T, ?>) renderer;
                }
            }
            if (ephemera$inExtractPass && entity == ephemera$lastEntity && ephemera$lastRenderer != null) {
                return (EntityRenderer<? super T, ?>) ephemera$lastRenderer;
            }
        }
        EntityRenderer<? super T, ?> resolved = (EntityRenderer<? super T, ?>) this.renderers.get(type);
        if (fast && resolved != null) {
            if (ephemera$inExtractPass) {
                ephemera$lastEntity = entity;
                ephemera$lastRenderer = resolved;
            }
            if (type instanceof RendererTypeCache cache) {
                cache.ephemera$renderer(this, ephemera$generation, resolved);
            }
        }
        return resolved;
    }

    /**
     * @author Ephemera
     * @reason Zero-allocation direct EntityType &amp; AvatarRenderState renderer lookup.
     */
    @SuppressWarnings("unchecked")
    @Overwrite
    public <S extends EntityRenderState> EntityRenderer<?, ? super S> getRenderer(S state) {
        boolean fast = EphemeraCommon.config().entityRendererDirectCache;
        if (state instanceof AvatarRenderState avatarState) {
            PlayerModelType model = avatarState.skin.model();
            if (fast) {
                if (!ephemera$avatarCachePopulated) {
                    ephemera$ensureAvatarCache();
                }
                AvatarRenderer<AbstractClientPlayer> r = model == PlayerModelType.SLIM
                        ? ephemera$slimPlayerRenderer
                        : ephemera$widePlayerRenderer;
                if (r != null) {
                    return (EntityRenderer<?, ? super S>) r;
                }
            }
            EntityRenderer<?, ? super S> r = (EntityRenderer<?, ? super S>) this.playerRenderers.get(model);
            return r != null ? r : (EntityRenderer<?, ? super S>) this.playerRenderers.get(PlayerModelType.WIDE);
        }
        EntityType<?> type = state.entityType;
        if (fast && type instanceof RendererTypeCache cache) {
            Object renderer = cache.ephemera$renderer(this, ephemera$generation);
            if (renderer != null) {
                return (EntityRenderer<?, ? super S>) renderer;
            }
        }
        EntityRenderer<?, ? super S> resolved = (EntityRenderer<?, ? super S>) this.renderers.get(type);
        if (fast && resolved != null && type instanceof RendererTypeCache cache) {
            cache.ephemera$renderer(this, ephemera$generation, resolved);
        }
        return resolved;
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void ephemera$reload(CallbackInfo ci) {
        ephemera$endExtractPass();
        ephemera$avatarCachePopulated = false;
        ephemera$widePlayerRenderer = null;
        ephemera$slimPlayerRenderer = null;
        ephemera$wideMannequinRenderer = null;
        ephemera$slimMannequinRenderer = null;
        for (EntityType<?> type : renderers.keySet()) ((RendererTypeCache) type).ephemera$clearRenderer();
        ephemera$generation++; FastShadowCache.invalidate(); WorldPreparedTextCache.invalidate();
    }
}
