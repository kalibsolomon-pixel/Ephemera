package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastQuadWriter;
import com.ephemera.render.fastwrite.FastVertexBuffer.SpriteExpander;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteCoordinateExpander.class)
public abstract class SpriteCoordinateExpanderFastWriteMixin implements VertexConsumer, SpriteExpander {
	@Override
	@Accessor("delegate")
	public abstract VertexConsumer ephemera$delegate();

	@Override
	@Accessor("sprite")
	public abstract TextureAtlasSprite ephemera$sprite();

	@Override
	public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		if (!FastQuadWriter.write(this, pose, quad, instance)) {
			VertexConsumer.super.putBakedQuad(pose, quad, instance);
		}
	}
}
