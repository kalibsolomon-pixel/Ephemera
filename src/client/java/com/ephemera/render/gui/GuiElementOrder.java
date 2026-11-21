package com.ephemera.render.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

import java.util.Comparator;

/**
 * Allocation-free equivalent of vanilla's {@code GuiRenderer.ELEMENT_SORT_COMPARATOR}: scissor area
 * (top, bottom, left, right; nulls first), then pipeline sort key, then texture setup sort key
 * (nulls first). Vanilla builds this from boxed {@code Comparator.comparing} chains, which allocate
 * and unbox on every comparison of every element in every layer.
 */
public final class GuiElementOrder implements Comparator<GuiElementRenderState> {
	public static final GuiElementOrder INSTANCE = new GuiElementOrder();

	private GuiElementOrder() {}

	@Override
	public int compare(GuiElementRenderState a, GuiElementRenderState b) {
		ScreenRectangle scissorA = a.scissorArea();
		ScreenRectangle scissorB = b.scissorArea();
		if (scissorA != scissorB) {
			if (scissorA == null) {
				return -1;
			}
			if (scissorB == null) {
				return 1;
			}
			int result = Integer.compare(scissorA.top(), scissorB.top());
			if (result != 0) {
				return result;
			}
			result = Integer.compare(scissorA.bottom(), scissorB.bottom());
			if (result != 0) {
				return result;
			}
			result = Integer.compare(scissorA.left(), scissorB.left());
			if (result != 0) {
				return result;
			}
			result = Integer.compare(scissorA.right(), scissorB.right());
			if (result != 0) {
				return result;
			}
		}
		int result = Integer.compare(a.pipeline().getSortKey(), b.pipeline().getSortKey());
		if (result != 0) {
			return result;
		}
		TextureSetup textureA = a.textureSetup();
		TextureSetup textureB = b.textureSetup();
		if (textureA == textureB) {
			return 0;
		}
		if (textureA == null) {
			return -1;
		}
		if (textureB == null) {
			return 1;
		}
		return Integer.compare(textureA.getSortKey(), textureB.getSortKey());
	}
}
