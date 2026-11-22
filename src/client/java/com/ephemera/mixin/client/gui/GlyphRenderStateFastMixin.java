package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiFeatures;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Eliminates the per-glyph {@code new Matrix4f()} allocation in {@link GlyphRenderState#buildVertices}
 * (measured at ~774 MB / 25s = 7.6% of all Render-thread heap allocation in JFR) via a
 * non-invasive {@link WrapOperation} on the {@code NEW Matrix4f} call site, preserving full
 * compatibility with mods injecting into {@code buildVertices}.
 */
@Mixin(GlyphRenderState.class)
abstract class GlyphRenderStateFastMixin {
	@Unique private static final Matrix4f EPHEMERA$SCRATCH = new Matrix4f();

	@WrapOperation(
		method = "buildVertices(Lcom/mojang/blaze3d/vertex/VertexConsumer;)V",
		at = @At(value = "NEW", target = "()Lorg/joml/Matrix4f;")
	)
	private Matrix4f ephemera$reusePoseMatrix(Operation<Matrix4f> original) {
		if (GuiFeatures.get().fastLtrText && RenderSystem.isOnRenderThread()) {
			return EPHEMERA$SCRATCH.identity();
		}
		return original.call();
	}
}
