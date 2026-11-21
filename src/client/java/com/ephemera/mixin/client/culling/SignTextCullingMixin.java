package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.BlockStateCullingManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignRenderer.class)
public abstract class SignTextCullingMixin {
    @Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
    private void ephemera$text(SignRenderState state, PoseStack pose, SubmitNodeCollector collector, SignText text, CallbackInfo ci) {
        if (BlockStateCullingManager.textFacingAway(pose.last().pose())) ci.cancel();
    }
}
