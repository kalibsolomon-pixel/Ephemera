package com.ephemera.render.backend;

import com.mojang.blaze3d.opengl.GlStateManager;
import java.util.Arrays;
import org.lwjgl.opengl.GL33C;

import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;

/** Render-thread GL state. Callers gate cached dispatch with {@link DispatchSettings#glDispatch()}. */
public final class GlStateCache {
	private static final int UNKNOWN = -1;
	private static final int[] boundSamplers = new int[32];
	private static final int[] uniformHandles = new int[32];
	private static final long[] uniformOffsets = new long[32];
	private static final long[] uniformLengths = new long[32];
	private static final int[][] DRAW_BUFFERS = new int[9][];
	private static int boundDrawIndirectBuffer;
	private static boolean scissorKnown;
	private static int scissorX;
	private static int scissorY;
	private static int scissorWidth;
	private static int scissorHeight;
	private static int polygonFace;
	private static int polygonMode;

	static {
		invalidateAll();
		for (int count = 0; count < DRAW_BUFFERS.length; count++) {
			int[] attachments = new int[count];
			for (int i = 0; i < count; i++) {
				attachments[i] = GL_COLOR_ATTACHMENT0 + i;
			}
			DRAW_BUFFERS[count] = attachments;
		}
	}

	private GlStateCache() {}

	public interface RenderPassState {
		int ephemera$colorAttachmentCount();

		void ephemera$applyScissor();
	}

	public static void bindSampler(int unit, int samplerId) {
		if (unit < 0 || unit >= boundSamplers.length) {
			GL33C.glBindSampler(unit, samplerId);
		} else if (boundSamplers[unit] != samplerId) {
			GL33C.glBindSampler(unit, samplerId);
			boundSamplers[unit] = samplerId;
		}
	}

	public static void onSamplerDeleted(int samplerId) {
		for (int unit = 0; unit < boundSamplers.length; unit++) {
			if (boundSamplers[unit] == samplerId) {
				boundSamplers[unit] = UNKNOWN;
			}
		}
	}

	public static void bindUniformBufferRange(int binding, int handle, long offset, long length) {
		if (binding < 0 || binding >= uniformHandles.length) {
			GL33C.glBindBufferRange(GL_UNIFORM_BUFFER, binding, handle, offset, length);
		} else if (uniformHandles[binding] != handle || uniformOffsets[binding] != offset
				|| uniformLengths[binding] != length) {
			GL33C.glBindBufferRange(GL_UNIFORM_BUFFER, binding, handle, offset, length);
			uniformHandles[binding] = handle;
			uniformOffsets[binding] = offset;
			uniformLengths[binding] = length;
		}
	}

	public static void onBufferDeleted(int handle) {
		for (int binding = 0; binding < uniformHandles.length; binding++) {
			if (uniformHandles[binding] == handle) {
				uniformHandles[binding] = UNKNOWN;
			}
		}
		if (boundDrawIndirectBuffer == handle) {
			invalidateDrawIndirect();
		}
	}

	public static void invalidateUniformBinding(int binding) {
		if (binding >= 0 && binding < uniformHandles.length) {
			uniformHandles[binding] = UNKNOWN;
		}
	}

	public static void bindDrawIndirectBuffer(int handle) {
		if (boundDrawIndirectBuffer != handle) {
			GlStateManager._glBindBuffer(GL_DRAW_INDIRECT_BUFFER, handle);
			boundDrawIndirectBuffer = handle;
		}
	}

	public static void invalidateDrawIndirect() {
		boundDrawIndirectBuffer = UNKNOWN;
	}

	public static boolean recordScissorBox(int x, int y, int width, int height) {
		if (scissorKnown && scissorX == x && scissorY == y && scissorWidth == width && scissorHeight == height) {
			return false;
		}
		scissorKnown = true;
		scissorX = x;
		scissorY = y;
		scissorWidth = width;
		scissorHeight = height;
		return true;
	}

	public static void invalidateScissorBox() {
		scissorKnown = false;
	}

	public static boolean recordPolygonMode(int face, int mode) {
		if (polygonFace == face && polygonMode == mode) {
			return false;
		}
		polygonFace = face;
		polygonMode = mode;
		return true;
	}

	public static void invalidatePolygonMode() {
		polygonFace = UNKNOWN;
		polygonMode = UNKNOWN;
	}

	public static void invalidateAll() {
		Arrays.fill(boundSamplers, UNKNOWN);
		Arrays.fill(uniformHandles, UNKNOWN);
		Arrays.fill(uniformOffsets, UNKNOWN);
		Arrays.fill(uniformLengths, UNKNOWN);
		invalidateDrawIndirect();
		invalidateScissorBox();
		invalidatePolygonMode();
	}

	/** Returns the shared, read-only attachment list, or null outside the cached range. */
	public static int[] drawBuffers(int count) {
		return count >= 0 && count < DRAW_BUFFERS.length ? DRAW_BUFFERS[count] : null;
	}
}
