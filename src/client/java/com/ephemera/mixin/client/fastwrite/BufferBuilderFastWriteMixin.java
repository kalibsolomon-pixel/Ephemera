package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastBufferLayout;
import com.ephemera.render.fastwrite.FastBufferState;
import com.ephemera.render.fastwrite.FastQuadWriter;
import com.ephemera.render.fastwrite.FastVertexLayout;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilder.class)
abstract class BufferBuilderFastWriteMixin implements VertexConsumer, FastBufferLayout {
	@Unique private FastVertexLayout ephemera$fastLayout;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$verifyQuadLayout(ByteBufferBuilder storage, PrimitiveTopology topology, VertexFormat format,
			CallbackInfo ci) {
		if (this instanceof FastBufferState builder) {
			if (topology == PrimitiveTopology.QUADS) {
				ephemera$fastLayout = FastVertexLayout.validate(format, builder);
			} else if (topology == PrimitiveTopology.LINES) {
				ephemera$fastLayout = FastVertexLayout.validateLines(format, builder);
			}
		}
	}

	@Override
	public FastVertexLayout ephemera$layout() {
		return ephemera$fastLayout;
	}

	@Override
	public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		if (!FastQuadWriter.write(this, pose, quad, instance)) {
			VertexConsumer.super.putBakedQuad(pose, quad, instance);
		}
	}

	@Override
	public void putBlockBakedQuad(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
		if (!FastQuadWriter.writeBlock(this, x, y, z, quad, instance)) {
			VertexConsumer.super.putBlockBakedQuad(x, y, z, quad, instance);
		}
	}
}
