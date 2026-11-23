package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.CompiledCuboid;
import com.ephemera.render.fastwrite.FastCuboidWriter;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.Cube.class)
abstract class ModelPartCubeFastWriteMixin {
	@Shadow @Final public ModelPart.Polygon[] polygons;
	@Unique private boolean ephemera$mirrored;
	@Unique private CompiledCuboid ephemera$compiled;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$rememberMirror(CallbackInfo ci, @Local(argsOnly = true) boolean mirror) {
		ephemera$mirrored = mirror;
		ephemera$compiled = CompiledCuboid.compile(polygons);
	}

	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void ephemera$writeCube(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color,
			CallbackInfo ci) {
		CompiledCuboid compiled = ephemera$compiled;
		if (compiled != null && FastCuboidWriter.writeCompiled(consumer, pose, compiled, polygons, light, overlay, color)) {
			ci.cancel();
			return;
		}
		if (FastCuboidWriter.write(consumer, pose, polygons, light, overlay, color, ephemera$mirrored)) {
			ci.cancel();
		}
	}
}
