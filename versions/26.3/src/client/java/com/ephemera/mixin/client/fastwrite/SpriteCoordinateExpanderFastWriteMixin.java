package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastQuadWriter;
import com.ephemera.render.fastwrite.FastVertexBuffer.SpriteExpander;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.3 {@code SpriteCoordinateExpander} wraps a {@code UvMapping}; {@code TextureAtlasSprite}
 * implements {@code UvMapping}, so unwrap it directly for {@link FastQuadWriter}.
 */
@Mixin(SpriteCoordinateExpander.class)
public abstract class SpriteCoordinateExpanderFastWriteMixin implements VertexConsumer, SpriteExpander {
	@Shadow @Final private UvMapping mapping;

	@Override
	@Accessor("delegate")
	public abstract VertexConsumer ephemera$delegate();

	@Override
	public TextureAtlasSprite ephemera$sprite() {
		return mapping instanceof TextureAtlasSprite sprite ? sprite : null;
	}

	@Override
	public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		if (!FastQuadWriter.write(this, pose, quad, instance)) {
			VertexConsumer.super.putBakedQuad(pose, quad, instance);
		}
	}
}
