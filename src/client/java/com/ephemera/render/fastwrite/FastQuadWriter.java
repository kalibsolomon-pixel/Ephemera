package com.ephemera.render.fastwrite;

import com.ephemera.core.render.VertexPacking;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix4f;

public final class FastQuadWriter {
	private FastQuadWriter() {}

	public static boolean write(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if ((layout != FastVertexLayout.BLOCK && layout != FastVertexLayout.ENTITY)
				|| !FastVertexBuffer.canAppend(builder, 4)) {
			return false;
		}
		FastVertexBuffer.Scratch scratch = FastVertexBuffer.acquire();
		if (scratch == null) {
			return false;
		}
		try {
			Matrix4f matrix = pose.pose();
			pose.transformNormal(quad.direction().getUnitVec3f(), scratch.vector);
			int normal = VertexPacking.normal(scratch.vector.x, scratch.vector.y, scratch.vector.z);
			int emission = quad.materialInfo().lightEmission();
			for (int i = 0; i < 4; i++) {
				long uv = quad.packedUV(i);
				int light = instance.getLightCoordsWithEmission(i, emission);
				matrix.transformPosition(quad.position(i), scratch.vector);
				scratch.vertex(i, instance.getColor(i), UVPair.unpackU(uv), UVPair.unpackV(uv), light, normal);
			}
			return scratch.append(builder, layout, 4, instance.overlayCoords());
		} finally {
			scratch.release();
		}
	}
}
