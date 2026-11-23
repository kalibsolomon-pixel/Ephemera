package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lightweight first-person {@link AvatarRenderState} extraction inside 26.3
 * {@code LevelExtractor#extractPlayerState}. In first-person view, {@code PlayerRenderState.avatarRenderState}
 * is read exclusively by {@code FirstPersonHandsAndItemsRenderer} (13 scalar fields),
 * {@code ScreenEffectRenderer} ({@code isSpectator}), and {@code EntityRenderDispatcher#getRenderer}
 * ({@code skin.model()}). Skipping full {@code extractEntity} avoids 6× {@code ItemModelResolver}
 * armor/equipment updates, cape physics, shadow extraction, and walk-cycle computation every frame.
 * Detached (third-person) cameras retain full vanilla extraction.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorPlayerStateFastMixin {
	@Shadow @Final private Minecraft minecraft;

	@Unique
	private final AvatarRenderState ephemera$firstPersonAvatar = new AvatarRenderState();

	@WrapOperation(
		method = "extractPlayerState",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
		)
	)
	private EntityRenderState ephemera$fastFirstPersonAvatarState(
		LevelExtractor instance, Entity entity, float partialTick,
		Operation<EntityRenderState> original,
		@Local(argsOnly = true) Camera camera
	) {
		if (!EphemeraCommon.config().entityRendererDirectCache
			|| camera.isDetached()
			|| !(entity instanceof LocalPlayer player)) {
			return original.call(instance, entity, partialTick);
		}
		AvatarRenderState state = this.ephemera$firstPersonAvatar;
		state.entityType = player.getType();
		state.x = Mth.lerp((double) partialTick, player.xOld, player.getX());
		state.y = Mth.lerp((double) partialTick, player.yOld, player.getY());
		state.z = Mth.lerp((double) partialTick, player.zOld, player.getZ());
		state.isInvisible = player.isInvisible();
		state.ageInTicks = (float) player.tickCount + partialTick;
		state.boundingBoxWidth = player.getBbWidth();
		state.boundingBoxHeight = player.getBbHeight();
		state.eyeHeight = player.getEyeHeight();
		state.xRot = player.getXRot(partialTick);
		state.isAutoSpinAttack = player.isAutoSpinAttack();
		state.ticksSinceKineticHitFeedback = player.getTicksSinceLastKineticHitFeedback(partialTick);
		state.mainArm = player.getMainArm();
		state.currentSwing = player.getCurrentSwing();
		state.swingAnimation = player.getSwingAnimation(partialTick);
		state.isUsingItem = player.isUsingItem();
		state.useItemHand = player.getUsedItemHand();
		state.skin = player.getSkin();
		state.isSpectator = player.isSpectator();
		state.showLeftSleeve = player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE);
		state.showRightSleeve = player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE);
		state.lightCoords = this.minecraft.getEntityRenderDispatcher().getPackedLightCoords(player, partialTick);
		return state;
	}
}
