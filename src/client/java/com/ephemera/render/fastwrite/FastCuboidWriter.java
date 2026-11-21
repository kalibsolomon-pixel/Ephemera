package com.ephemera.render.fastwrite;

import com.ephemera.core.render.VertexPacking;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;

public final class FastCuboidWriter {
	private FastCuboidWriter() {}

	public static boolean write(VertexConsumer consumer, PoseStack.Pose pose, ModelPart.Polygon[] polygons,
			int light, int overlay, int color) {
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		int vertexCount = polygons.length * 4;
		if ((layout != FastVertexLayout.BLOCK && layout != FastVertexLayout.ENTITY)
				|| polygons.length == 0 || polygons.length > 6 || !FastVertexBuffer.canAppend(builder, vertexCount)) {
			return false;
		}
		for (ModelPart.Polygon polygon : polygons) {
			if (polygon == null || polygon.vertices() == null || polygon.vertices().length != 4) {
				return false;
			}
		}
		FastVertexBuffer.Scratch scratch = FastVertexBuffer.acquire();
		if (scratch == null) {
			return false;
		}
		try {
			Matrix4f matrix = pose.pose();
			int i = 0;
			for (ModelPart.Polygon polygon : polygons) {
				pose.transformNormal(polygon.normal(), scratch.vector);
				int normal = VertexPacking.normal(scratch.vector.x, scratch.vector.y, scratch.vector.z);
				for (ModelPart.Vertex vertex : polygon.vertices()) {
					matrix.transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), scratch.vector);
					scratch.vertex(i++, color, vertex.u(), vertex.v(), light, normal);
				}
			}
			return scratch.append(builder, layout, vertexCount, overlay);
		} finally {
			scratch.release();
		}
	}
}
