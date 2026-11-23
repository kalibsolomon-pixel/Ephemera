package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastVertexTransforms.PoseAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoseStack.Pose.class)
public interface PoseStackPoseAccess extends PoseAccess {
	@Override
	@Accessor("trustedNormals")
	boolean ephemera$trustedNormals();
}
