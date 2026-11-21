package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/** Explicit byte offsets for the supported 26.2 default formats. */
public enum FastVertexLayout {
	BLOCK(DefaultVertexFormat.BLOCK, 28, 12, 16, -1, 24, -1),
	ENTITY(DefaultVertexFormat.ENTITY, 36, 12, 16, 24, 28, 32),
	PARTICLE(DefaultVertexFormat.PARTICLE, 28, 20, 12, -1, 24, -1),
	UI_TEXT(DefaultVertexFormat.POSITION_TEX_COLOR, 24, 20, 12, -1, -1, -1),
	WORLD_TEXT(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR, 28, 24, 12, -1, 20, -1),
	COLOR_FIRST_TEXT(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, 28, 12, 16, -1, 24, -1);

	private static final FastVertexLayout[] LAYOUTS = values();

	final int stride;
	final int color;
	final int uv;
	final int overlay;
	final int light;
	final int normal;
	private final VertexFormat format;
	private final boolean validated;
	private final int mask;

	FastVertexLayout(VertexFormat format, int stride, int color, int uv, int overlay, int light, int normal) {
		this.format = format;
		this.stride = stride;
		this.color = color;
		this.uv = uv;
		this.overlay = overlay;
		this.light = light;
		this.normal = normal;
		this.mask = 0b110 | (overlay >= 0 ? 1 << 3 : 0) | (light >= 0 ? 1 << 4 : 0)
			| (normal >= 0 ? 1 << 5 : 0);
		this.validated = format.getStepRate() == 0 && format.getVertexSize() == stride
			&& format.getElements().size() == Integer.bitCount(mask) + 1
			&& matches(format, "Position", GpuFormat.RGB32_FLOAT, 0)
			&& matches(format, "Color", GpuFormat.RGBA8_UNORM, color)
			&& matches(format, "UV0", GpuFormat.RG32_FLOAT, uv)
			&& matches(format, "UV1", GpuFormat.RG16_SINT, overlay)
			&& matches(format, "UV2", GpuFormat.RG16_SINT, light)
			&& matches(format, "Normal", GpuFormat.RGBA8_SNORM, normal)
			&& format.getElement("LineWidth") == null;
	}

	public static FastVertexLayout validate(VertexFormat format, FastBufferState builder) {
		for (FastVertexLayout layout : LAYOUTS) {
			if (format == layout.format && layout.validated && layout.matchesBuilder(builder)) {
				return layout;
			}
		}
		return null;
	}

	private boolean matchesBuilder(FastBufferState builder) {
		VertexFormatElement[] elements = builder.ephemera$elements();
		return builder.ephemera$vertexSize() == stride && builder.ephemera$initialElementsToFill() == mask
			&& elements.length == 7 && elements[0] == format.getElement("Position")
			&& elements[1] == format.getElement("Color") && elements[2] == format.getElement("UV0")
			&& elements[3] == format.getElement("UV1") && elements[4] == format.getElement("UV2")
			&& elements[5] == format.getElement("Normal") && elements[6] == null;
	}

	private static boolean matches(VertexFormat format, String name, GpuFormat type, int offset) {
		VertexFormatElement element = format.getElement(name);
		return offset < 0 ? element == null : element != null && element.format() == type && element.offset() == offset;
	}
}
