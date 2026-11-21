package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/** Explicit byte offsets for the supported 26.2 default formats. */
public enum FastVertexLayout {
	BLOCK(DefaultVertexFormat.BLOCK, 28, 12, 16, -1, 24, -1, true),
	ENTITY(DefaultVertexFormat.ENTITY, 36, 12, 16, 24, 28, 32, true),
	POSITION_TEX_COLOR_NORMAL(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, 28, 20, 12, -1, -1, 24, true),
	POSITION_TEX(DefaultVertexFormat.POSITION_TEX, 20, -1, 12, -1, -1, -1, true),
	PARTICLE(DefaultVertexFormat.PARTICLE, 28, 20, 12, -1, 24, -1, false),
	UI_TEXT(DefaultVertexFormat.POSITION_TEX_COLOR, 24, 20, 12, -1, -1, -1, false),
	WORLD_TEXT(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR, 28, 24, 12, -1, 20, -1, false),
	COLOR_FIRST_TEXT(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, 28, 12, 16, -1, 24, -1, false);

	private static final FastVertexLayout[] LAYOUTS = values();

	final int stride;
	final int color;
	final int uv;
	final int overlay;
	final int light;
	final int normal;
	final boolean model;
	private final VertexFormat format;
	private final boolean validated;
	private final int mask;
	private final VertexFormatElement elPos;
	private final VertexFormatElement elColor;
	private final VertexFormatElement elUv0;
	private final VertexFormatElement elUv1;
	private final VertexFormatElement elUv2;
	private final VertexFormatElement elNormal;

	FastVertexLayout(VertexFormat format, int stride, int color, int uv, int overlay, int light, int normal, boolean model) {
		this.format = format;
		this.stride = stride;
		this.color = color;
		this.uv = uv;
		this.overlay = overlay;
		this.light = light;
		this.normal = normal;
		this.model = model;
		this.mask = (color >= 0 ? 1 << 1 : 0) | (uv >= 0 ? 1 << 2 : 0) | (overlay >= 0 ? 1 << 3 : 0)
			| (light >= 0 ? 1 << 4 : 0) | (normal >= 0 ? 1 << 5 : 0);
		this.elPos = format.getElement("Position");
		this.elColor = format.getElement("Color");
		this.elUv0 = format.getElement("UV0");
		this.elUv1 = format.getElement("UV1");
		this.elUv2 = format.getElement("UV2");
		this.elNormal = format.getElement("Normal");
		this.validated = format.getStepRate() == 0 && format.getVertexSize() == stride
			&& format.getElements().size() == Integer.bitCount(mask) + 1
			&& matches(elPos, GpuFormat.RGB32_FLOAT, 0)
			&& matches(elColor, GpuFormat.RGBA8_UNORM, color)
			&& matches(elUv0, GpuFormat.RG32_FLOAT, uv)
			&& matches(elUv1, GpuFormat.RG16_SINT, overlay)
			&& matches(elUv2, GpuFormat.RG16_SINT, light)
			&& matches(elNormal, GpuFormat.RGBA8_SNORM, normal)
			&& format.getElement("LineWidth") == null;
	}

	public static FastVertexLayout validate(VertexFormat format, FastBufferState builder) {
		for (FastVertexLayout layout : LAYOUTS) {
			if (format == layout.format) {
				return layout.validated && layout.matchesBuilder(builder) ? layout : null;
			}
		}
		return null;
	}

	private boolean matchesBuilder(FastBufferState builder) {
		VertexFormatElement[] elements = builder.ephemera$elements();
		return builder.ephemera$vertexSize() == stride && builder.ephemera$initialElementsToFill() == mask
			&& elements.length == 7 && elements[0] == elPos
			&& elements[1] == elColor && elements[2] == elUv0
			&& elements[3] == elUv1 && elements[4] == elUv2
			&& elements[5] == elNormal && elements[6] == null;
	}

	private static boolean matches(VertexFormatElement element, GpuFormat type, int offset) {
		return offset < 0 ? element == null : element != null && element.format() == type && element.offset() == offset;
	}
}
