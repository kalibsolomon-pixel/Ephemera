package com.ephemera.mixin.client.compat;

import com.ephemera.render.fastwrite.FastLineWriter;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cooperative fast-write bridge for Aurora Client's SDF hitbox and block-outline renderer
 * ({@code WorldLineRenderer}). Applied only when {@code aurora} is loaded and fail-soft ({@code require = 0}).
 * Collapses 48–112 per-vertex {@code BufferBuilder.addVertex} + {@code endLastVertex} copy cycles per SDF
 * hitbox into a single native buffer reservation with bit-identical vertex output.
 */
@Pseudo
@Mixin(targets = "com.aurora.client.hud.WorldLineRenderer", remap = false)
public abstract class AuroraWorldLineFastMixin {
	@Inject(method = "drawSdfBox(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;Lnet/minecraft/world/phys/Vec3;DDDDDDFFFFF)V",
		at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private static void ephemera$fastDrawSdfBox(VertexConsumer buf, Matrix4f m, Vec3 camPos,
			double x1, double y1, double z1, double x2, double y2, double z2,
			float r, float g, float b, float a, float width, CallbackInfo ci) {
		if (FastLineWriter.writeSdfBox(buf, m, camPos, x1, y1, z1, x2, y2, z2, r, g, b, a, width)) {
			ci.cancel();
		}
	}

	@Inject(method = "drawLine(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;Lnet/minecraft/world/phys/Vec3;DDDDDDFFFFFZ)V",
		at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private static void ephemera$fastDrawLine(VertexConsumer buf, Matrix4f m, Vec3 camPos,
			double x1, double y1, double z1, double x2, double y2, double z2,
			float r, float g, float b, float a, float width, boolean sdf, CallbackInfo ci) {
		if (FastLineWriter.writeLine(buf, m, camPos, x1, y1, z1, x2, y2, z2, r, g, b, a, width, sdf)) {
			ci.cancel();
		}
	}
}
