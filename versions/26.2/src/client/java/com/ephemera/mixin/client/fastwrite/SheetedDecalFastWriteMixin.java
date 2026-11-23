package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastQuadWriter;
import com.ephemera.render.fastwrite.FastVertexBuffer.SheetedDecal;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SheetedDecalTextureGenerator.class)
public abstract class SheetedDecalFastWriteMixin implements VertexConsumer, SheetedDecal {
	@Override
	@Accessor("delegate")
	public abstract VertexConsumer ephemera$delegate();

	@Override
	@Accessor("cameraInversePose")
	public abstract Matrix4f ephemera$cameraInversePose();

	@Override
	@Accessor("normalInversePose")
	public abstract Matrix3f ephemera$normalInversePose();

	@Override
	@Accessor("textureScale")
	public abstract float ephemera$textureScale();

	@Override
	public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		if (!FastQuadWriter.writeSheetedDecal(this, pose, quad, instance)) {
			VertexConsumer.super.putBakedQuad(pose, quad, instance);
		}
	}
}
