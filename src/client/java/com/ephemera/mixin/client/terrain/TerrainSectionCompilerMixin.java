package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.FastSectionCompiler;
import com.ephemera.render.terrain.RegionVertexBaking;
import com.ephemera.render.terrain.TerrainFeatures;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Non-replacing hooks into vanilla section compilation (Fabric's renderer API redirects the
 * tesselation call inside {@code compile}, so the method body is left intact):
 * <ul>
 *   <li>before the block loop, a per-thread {@link FastSectionCompiler.Pass} is prepared;</li>
 *   <li>each block's {@code getRenderShape()} result is reported as {@code INVISIBLE} when the
 *   block is buried and would emit no quads, which skips both vanilla and Fabric tesselation
 *   while leaving visibility, block entities and fluids untouched;</li>
 *   <li>on return, the section's animated sprite ids are attached to the results and its
 *   vertices are rebased / bucketed by {@link RegionVertexBaking}.</li>
 * </ul>
 */
@Mixin(SectionCompiler.class)
public abstract class TerrainSectionCompilerMixin {
	@Shadow
	@Final
	private BlockStateModelSet blockModelSet;

	@Shadow
	@Final
	private FluidStateModelSet fluidModelSet;

	@Inject(
		method = "compile",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;")
	)
	private void ephemera$beginSection(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting,
									   SectionBufferBuilderPack builders, CallbackInfoReturnable<SectionCompiler.Results> cir,
									   @Share("ephemera$pass") LocalRef<FastSectionCompiler.Pass> pass) {
		pass.set(FastSectionCompiler.begin(sectionPos, region, blockModelSet, fluidModelSet));
	}

	@ModifyExpressionValue(
		method = "compile",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;")
	)
	private RenderShape ephemera$skipBuriedBlock(RenderShape shape, @Local BlockState blockState, @Local(ordinal = 2) BlockPos pos,
												 @Share("ephemera$pass") LocalRef<FastSectionCompiler.Pass> pass) {
		FastSectionCompiler.Pass p = pass.get();
		if (p == null || shape != RenderShape.MODEL) {
			return shape;
		}
		try {
			return p.onModelBlock(blockState, pos) ? RenderShape.INVISIBLE : shape;
		} catch (Throwable t) {
			TerrainFeatures.disableCompiler(t);
			pass.set(null);
			return shape;
		}
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void ephemera$endSection(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting,
									 SectionBufferBuilderPack builders, CallbackInfoReturnable<SectionCompiler.Results> cir,
									 @Share("ephemera$pass") LocalRef<FastSectionCompiler.Pass> pass) {
		FastSectionCompiler.Pass p = pass.get();
		if (p != null) {
			pass.set(null);
			p.finish(cir.getReturnValue());
		}
		SectionCompiler.Results results = cir.getReturnValue();
		if (results != null) {
			RegionVertexBaking.bake(sectionPos, results);
		}
	}
}
