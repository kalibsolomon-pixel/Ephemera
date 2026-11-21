package com.ephemera.mixin.client.visuals;

import com.ephemera.visuals.AoContrast;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Contrast/gamma polish on the four corner shades smooth lighting computes per quad. */
@Mixin(BlockModelLighter.class)
public abstract class BlockModelLighterAoMixin {
	@Inject(method = "prepareQuadAmbientOcclusion", at = @At("TAIL"))
	private void ephemera$aoContrast(BlockAndTintGetter level, BlockState state, BlockPos pos, BakedQuad quad, QuadInstance output, CallbackInfo ci) {
		AoContrast.apply(output);
	}
}
