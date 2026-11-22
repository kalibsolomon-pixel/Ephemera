package com.ephemera.render.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;

/**
 * Render-thread replacement for vanilla's per-glyph {@code GlyphRenderState} emission in
 * {@code GuiRenderer.prepareText}. Collapses each contiguous {@code (RenderPipeline, GpuTextureView)}
 * run of a {@link GuiTextRenderState} into one pooled {@link GuiElementRenderState}, eliminating the
 * per-character {@code GlyphRenderState} / visitor allocations and the per-character
 * {@code addElementToMesh} / {@code TextureSetup} / {@code Matrix4f.mul} overhead.
 *
 * <p>Glyphs and runs are retained until {@link #reset()}, which must only run after the frame's
 * {@code GuiRenderState} has been drawn and reset.
 */
public final class GuiTextBatcher implements Font.GlyphVisitor {
	/** Vanilla {@code GlyphRenderState.buildVertices} light value ({@code 0xF000F0}). */
	private static final int FULL_BRIGHT = 15728880;

	TextRenderable[] glyphs = new TextRenderable[512];
	int glyphCount;
	private Run[] runs = new Run[64];
	private int runCount;
	final Matrix4f scratchMatrix = new Matrix4f();

	private GuiRenderState activeRenderState;
	private Matrix3x2fc activePose;
	private ScreenRectangle activeScissor;
	private RenderPipeline runPipeline;
	private GpuTextureView runTexture;
	private int runStart;

	public void emit(GuiRenderState renderState, GuiTextRenderState textState) {
		activeRenderState = renderState;
		activePose = textState.pose;
		activeScissor = textState.scissor;
		runPipeline = null;
		runTexture = null;
		runStart = glyphCount;
		try {
			textState.ensurePrepared().visit(this);
			flushRun();
		} finally {
			activeRenderState = null;
			activePose = null;
			activeScissor = null;
			runPipeline = null;
			runTexture = null;
		}
	}

	@Override
	public void acceptRenderable(TextRenderable renderable) {
		if (renderable == null) {
			return;
		}
		RenderPipeline pipeline = renderable.guiPipeline();
		GpuTextureView texture = renderable.textureView();
		if (glyphCount > runStart && (pipeline != runPipeline || texture != runTexture)) {
			flushRun();
		}
		if (glyphCount == runStart) {
			runPipeline = pipeline;
			runTexture = texture;
		}
		if (glyphCount == glyphs.length) {
			glyphs = Arrays.copyOf(glyphs, glyphs.length << 1);
		}
		glyphs[glyphCount++] = renderable;
	}

	private void flushRun() {
		if (glyphCount <= runStart) {
			return;
		}
		if (runCount == runs.length) {
			runs = Arrays.copyOf(runs, runs.length << 1);
		}
		Run run = runs[runCount];
		if (run == null) {
			run = runs[runCount] = new Run();
		}
		runCount++;
		// Same call as vanilla GlyphRenderState.textureSetup() so TextureSetupCacheMixin & Polytone hooks apply
		TextureSetup setup = TextureSetup.singleTextureWithLightmap(runTexture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		run.set(activePose, activeScissor, runPipeline, setup, runStart, glyphCount);
		activeRenderState.addGlyphToCurrentLayer(run);
		runStart = glyphCount;
		runPipeline = null;
		runTexture = null;
	}

	public void reset() {
		Arrays.fill(glyphs, 0, glyphCount, null);
		for (int i = 0; i < runCount; i++) {
			runs[i].clear();
		}
		if (glyphs.length > 8192 && glyphCount < 1024) {
			glyphs = new TextRenderable[1024];
		}
		if (runs.length > 1024 && runCount < 128) {
			runs = new Run[128];
		}
		glyphCount = 0;
		runCount = 0;
		runStart = 0;
		activeRenderState = null;
		activePose = null;
		activeScissor = null;
		runPipeline = null;
		runTexture = null;
	}

	/** One contiguous same-pipeline, same-texture glyph run; reads the enclosing (possibly grown) glyph array. */
	final class Run implements GuiElementRenderState {
		private Matrix3x2fc pose;
		private ScreenRectangle scissor;
		private RenderPipeline pipeline;
		private TextureSetup textureSetup;
		private int start;
		private int end;

		void set(Matrix3x2fc pose, ScreenRectangle scissor, RenderPipeline pipeline, TextureSetup textureSetup, int start, int end) {
			this.pose = pose;
			this.scissor = scissor;
			this.pipeline = pipeline;
			this.textureSetup = textureSetup;
			this.start = start;
			this.end = end;
		}

		void clear() {
			pose = null;
			scissor = null;
			pipeline = null;
			textureSetup = null;
			start = end = 0;
		}

		@Override
		public void buildVertices(VertexConsumer consumer) {
			Matrix4f mat = scratchMatrix.identity().mul(pose);
			TextRenderable[] array = glyphs;
			for (int i = start, limit = end; i < limit; i++) {
				array[i].render(mat, consumer, FULL_BRIGHT, true);
			}
		}

		@Override
		public RenderPipeline pipeline() {
			return pipeline;
		}

		@Override
		public TextureSetup textureSetup() {
			return textureSetup;
		}

		@Override
		public ScreenRectangle scissorArea() {
			return scissor;
		}

		@Override
		public ScreenRectangle bounds() {
			return null;
		}
	}
}
