package com.ephemera.render.terrain.gl;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.render.backend.GlStateCache;
import com.ephemera.visuals.VisualsRuntime;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenGL single multi-draw terrain: every solid (and every cutout) draw on one vertex heap
 * runs in one {@code glMultiDrawElementsIndirect}, whatever region or fading section it
 * belongs to; consecutive translucent draws on the same heaps merge into one back-to-front
 * run the same way. Each draw's {@code firstInstance} is {@code 1 + entry} into the
 * {@code EphemeraTerrainBatch} uniform block, which holds {@code (originX, originY, originZ,
 * floatBits(visibility))}; the patched {@code core/terrain} shaders read it through
 * {@code gl_BaseInstanceARB}. Base instance 0 keeps vanilla's {@code ChunkSection} values, so
 * every other draw is unaffected.
 *
 * <p>Entries live in four persistent, coherently mapped uniform buffers, rotated whenever a
 * frame writes entries, so a frame never overwrites entries an earlier frame may still read.
 * A CPU shadow copy lets a reused plan rewrite only fading visibilities without reading back
 * write-only mapped memory.
 *
 * <p>Stays off unless the device has every needed extension and both the solid and cutout
 * programs were linked with the batch block (a resource pack that changes the terrain shaders
 * keeps the patch from applying, which leaves the regular per-region path in charge).
 * Render thread only.
 */
public final class GlSingleMdiTerrain {
	/** GL 3.3 guarantees at least 12 vertex uniform block bindings; vanilla uses only the first few. */
	public static final int BATCH_UBO_BINDING = 11;
	private static final int FRAMES = 4;
	private static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/GlSingleMdi");
	private static final GlSingleMdiTerrain INSTANCE = new GlSingleMdiTerrain();

	// Capability probe, once per device.
	private @Nullable GpuDevice probedDevice;
	private boolean supported;
	private int maxEntries = 1024;
	private boolean failed;

	// Shader patch and program tracking. Patch state is kept per shader-defines variant, the key
	// GlDevice caches compiled shaders by (with the shader id), so a cache hit still pairs correctly.
	private static final Identifier TERRAIN_SHADER = Identifier.withDefaultNamespace("core/terrain");
	private @Nullable Identifier compilingVertexShader;
	private @Nullable ShaderDefines compilingDefines;
	private final Set<ShaderDefines> patchedVertexDefines = new HashSet<>();
	private final Set<ShaderDefines> patchedFragmentDefines = new HashSet<>();
	private final Int2ObjectOpenHashMap<String> batchPrograms = new Int2ObjectOpenHashMap<>();
	private int programGeneration;
	private int verifiedGeneration = -1;
	private int precompiledGeneration = -1;
	private boolean programsVerified;
	private boolean translucentVerified;

	// Zeroed block kept bound while the ring is not, so patched programs never draw unbacked.
	private @Nullable GpuDevice fallbackDevice;
	private int fallbackUbo;

	// Uniform ring.
	private @Nullable GpuDevice device;
	private final int[] uboHandles = new int[FRAMES];
	private final long[] mappedPtrs = new long[FRAMES];
	private long shadowPtr;
	private long ringBytes;
	private boolean ready;
	private int frameSlot;
	private int entryCount;

	private GlSingleMdiTerrain() {}

	public static GlSingleMdiTerrain get() {
		return INSTANCE;
	}

	/** Entries per frame, as baked into the patched vertex shader's array size. */
	public static int maxEntries() {
		return INSTANCE.maxEntries;
	}

	/** True when {@code core/terrain} should be compiled with the batch lookup. */
	public static boolean shouldPatchShaders() {
		if (!MixinHealth.applied().contains("GlProgramTerrainBatchMixin")) {
			return false;
		}
		GlSingleMdiTerrain t = INSTANCE;
		GpuDevice d = RenderSystem.tryGetDevice();
		return d != null && t.probe(d) && !t.failed && configEnabled() && !VisualsRuntime.externalShadersActive();
	}

	/** {@code GlDevice.compileProgram} is linking a pipeline with this vertex shader and these defines. */
	public static void beginProgramCompile(Identifier vertexShader, ShaderDefines defines) {
		INSTANCE.compilingVertexShader = vertexShader;
		INSTANCE.compilingDefines = defines;
	}

	public static void endProgramCompile() {
		INSTANCE.compilingVertexShader = null;
		INSTANCE.compilingDefines = null;
	}

	/** The vertex shader being compiled belongs to a terrain pipeline, so it may carry the batch lookup. */
	public static boolean compilingTerrainProgram() {
		return INSTANCE.compilingDefines != null && TERRAIN_SHADER.equals(INSTANCE.compilingVertexShader);
	}

	public static void recordVertexPatched(boolean patched) {
		GlSingleMdiTerrain t = INSTANCE;
		if (t.compilingDefines != null) {
			if (patched) {
				t.patchedVertexDefines.add(t.compilingDefines);
			} else {
				t.patchedVertexDefines.remove(t.compilingDefines);
			}
		}
	}

	/**
	 * Whether the fragment shader being compiled may read the batch varying: its own pipeline's
	 * vertex stage is {@code core/terrain} with the same defines, and that variant was patched.
	 */
	public static boolean shouldPatchFragment() {
		GlSingleMdiTerrain t = INSTANCE;
		return compilingTerrainProgram() && t.patchedVertexDefines.contains(t.compilingDefines);
	}

	public static void recordFragmentPatched(boolean patched) {
		GlSingleMdiTerrain t = INSTANCE;
		if (t.compilingDefines != null) {
			if (patched) {
				t.patchedFragmentDefines.add(t.compilingDefines);
			} else {
				t.patchedFragmentDefines.remove(t.compilingDefines);
			}
		}
	}

	private static boolean configEnabled() {
		EphemeraConfig cfg = EphemeraCommon.config();
		return cfg.terrainEngineEnabled && cfg.terrainMultiDraw;
	}

	private boolean probe(GpuDevice d) {
		if (d == probedDevice) {
			return supported;
		}
		probedDevice = d;
		supported = false;
		try {
			if (!"OpenGL".equals(d.getDeviceInfo().backendName())) {
				return false;
			}
			DeviceFeatures f = d.getDeviceInfo().features();
			GLCapabilities caps = GL.getCapabilities();
			boolean ok = f.drawIndirect() && f.multiDrawIndirect() && f.shaderDrawParameters()
				&& f.nonZeroFirstInstance() && f.persistentMapping()
				&& caps.GL_ARB_multi_draw_indirect && caps.GL_ARB_shader_draw_parameters
				&& caps.GL_ARB_base_instance && caps.GL_ARB_buffer_storage;
			if (ok) {
				int maxBlock = GL33C.glGetInteger(GL31C.GL_MAX_UNIFORM_BLOCK_SIZE);
				maxEntries = maxBlock >= 65536 ? 4096 : 1024;
				supported = true;
				LOGGER.info("Ephemera OpenGL single multi-draw terrain available ({} entries per frame)", maxEntries);
			} else {
				LOGGER.info("Ephemera OpenGL single multi-draw terrain unavailable: missing multi-draw indirect, draw parameters, base instance or buffer storage");
			}
		} catch (Throwable t) {
			LOGGER.debug("Ephemera OpenGL single multi-draw probe failed: {}", t.toString());
			supported = false;
			// e.g. no current context on this thread: probe again next time.
			probedDevice = null;
		}
		return supported;
	}

	// ---------------------------------------------------------------------------------------
	// Program tracking (GlProgramTerrainBatchMixin)
	// ---------------------------------------------------------------------------------------

	/** A linked program declares the batch block (already bound to {@link #BATCH_UBO_BINDING}). */
	public void onProgramBound(int programId, String debugLabel) {
		batchPrograms.put(programId, debugLabel);
		programGeneration++;
		if (!ready) {
			try {
				bindFallbackUbo();
			} catch (Throwable t) {
				LOGGER.debug("Ephemera OpenGL single multi-draw fallback block bind failed: {}", t.toString());
			}
		}
	}

	public void onProgramClosed(int programId) {
		if (batchPrograms.remove(programId) != null) {
			programGeneration++;
		}
	}

	private boolean verifyPrograms(GpuDevice d) {
		if (verifiedGeneration == programGeneration) {
			return programsVerified;
		}
		if (precompiledGeneration != programGeneration) {
			// Links both programs now (cached when they already are) so they register before the first draw.
			precompiledGeneration = programGeneration;
			try {
				d.precompilePipeline(ChunkSectionLayer.SOLID.pipeline());
				d.precompilePipeline(ChunkSectionLayer.CUTOUT.pipeline());
				d.precompilePipeline(ChunkSectionLayer.TRANSLUCENT.pipeline());
			} catch (Throwable t) {
				LOGGER.warn("Ephemera OpenGL single multi-draw: terrain pipeline precompile failed: {}", t.toString());
			}
		}
		String solid = ChunkSectionLayer.SOLID.pipeline().getLocation().toString();
		String cutout = ChunkSectionLayer.CUTOUT.pipeline().getLocation().toString();
		String translucent = ChunkSectionLayer.TRANSLUCENT.pipeline().getLocation().toString();
		boolean hasSolid = false;
		boolean hasCutout = false;
		boolean hasTranslucent = false;
		for (String label : batchPrograms.values()) {
			hasSolid |= solid.equals(label);
			hasCutout |= cutout.equals(label);
			hasTranslucent |= translucent.equals(label);
		}
		boolean verified = hasSolid && hasCutout
			&& fragmentPatched(ChunkSectionLayer.SOLID) && fragmentPatched(ChunkSectionLayer.CUTOUT);
		boolean withTranslucent = verified && hasTranslucent && fragmentPatched(ChunkSectionLayer.TRANSLUCENT);
		if (verified != programsVerified || withTranslucent != translucentVerified) {
			LOGGER.info(!verified
				? "Ephemera OpenGL single multi-draw terrain inactive: terrain shaders were not patched"
				: withTranslucent
					? "Ephemera OpenGL single multi-draw terrain active (solid, cutout, translucent)"
					: "Ephemera OpenGL single multi-draw terrain active (solid, cutout; translucent shader was not patched)");
		}
		programsVerified = verified;
		translucentVerified = withTranslucent;
		verifiedGeneration = programGeneration;
		return verified;
	}

	private boolean fragmentPatched(ChunkSectionLayer layer) {
		return patchedFragmentDefines.contains(layer.pipeline().getShaderDefines());
	}

	/**
	 * Whether translucent draws may use batch entries too: its program was linked with the
	 * batch block. Valid after a {@link #beginFrame} that returned true.
	 */
	public boolean supportsTranslucent() {
		return programsVerified && translucentVerified;
	}

	// ---------------------------------------------------------------------------------------
	// Frame
	// ---------------------------------------------------------------------------------------

	/** Whether this frame's terrain may use single multi-draw. Call once per frame before building. */
	public boolean beginFrame() {
		if (failed) {
			return false;
		}
		GpuDevice d = RenderSystem.tryGetDevice();
		if (d == null || !probe(d) || !configEnabled() || VisualsRuntime.externalShadersActive()) {
			return false;
		}
		try {
			if (!verifyPrograms(d)) {
				return false;
			}
			ensureInitialized(d);
		} catch (Throwable t) {
			fail(t);
			return false;
		}
		return ready;
	}

	/** Starts a fresh entry list in the next ring slot (plan rebuild). */
	public void beginBuild() {
		frameSlot = (frameSlot + 1) % FRAMES;
		entryCount = 0;
	}

	/** Appends one entry; returns its index, or -1 when the block is full. */
	public int addEntry(int ox, int oy, int oz, float visibility) {
		int idx = entryCount;
		if (idx >= maxEntries || !ready) {
			return -1;
		}
		long off = (long) idx << 4;
		int vis = Float.floatToRawIntBits(visibility);
		long dst = mappedPtrs[frameSlot] + off;
		long shadow = shadowPtr + off;
		MemoryUtil.memPutInt(dst, ox);
		MemoryUtil.memPutInt(dst + 4L, oy);
		MemoryUtil.memPutInt(dst + 8L, oz);
		MemoryUtil.memPutInt(dst + 12L, vis);
		MemoryUtil.memPutInt(shadow, ox);
		MemoryUtil.memPutInt(shadow + 4L, oy);
		MemoryUtil.memPutInt(shadow + 8L, oz);
		MemoryUtil.memPutInt(shadow + 12L, vis);
		entryCount = idx + 1;
		return idx;
	}

	/**
	 * Reused plan: rewrites the visibility of {@code count} fading entries into a fresh ring
	 * slot (copied from the shadow, so every other entry stays). With nothing fading the
	 * current slot keeps being drawn with no writes at all.
	 */
	public void prepareReuseForFading(int[] entryIndices, float[] visibilities, int count) {
		if (count <= 0 || !ready) {
			return;
		}
		for (int i = 0; i < count; i++) {
			MemoryUtil.memPutInt(shadowPtr + ((long) entryIndices[i] << 4) + 12L, Float.floatToRawIntBits(visibilities[i]));
		}
		frameSlot = (frameSlot + 1) % FRAMES;
		MemoryUtil.memCopy(shadowPtr, mappedPtrs[frameSlot], (long) entryCount << 4);
	}

	public int entryCount() {
		return entryCount;
	}

	/**
	 * Binds the current slot to {@link #BATCH_UBO_BINDING}, once per terrain pass. The
	 * whole block is bound (the shader declares the full array), and unconditionally, so state
	 * changed by anything else in between never matters.
	 */
	public void bindBatchUbo() {
		if (!ready) {
			return;
		}
		GlStateCache.invalidateUniformBinding(BATCH_UBO_BINDING);
		GL33C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, BATCH_UBO_BINDING, uboHandles[frameSlot], 0L, ringBytes);
	}

	private void ensureInitialized(GpuDevice d) {
		if (d != device) {
			// Buffers of a previous device died with its context.
			forgetBuffers();
			device = d;
		}
		if (ready) {
			return;
		}
		long bytes = (long) maxEntries << 4;
		int flags = GL30C.GL_MAP_WRITE_BIT | ARBBufferStorage.GL_MAP_PERSISTENT_BIT | ARBBufferStorage.GL_MAP_COHERENT_BIT;
		try {
			for (int i = 0; i < FRAMES; i++) {
				int h = GL33C.glGenBuffers();
				uboHandles[i] = h;
				GL33C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, h);
				ARBBufferStorage.glBufferStorage(GL31C.GL_UNIFORM_BUFFER, bytes, flags);
				long ptr = GL30C.nglMapBufferRange(GL31C.GL_UNIFORM_BUFFER, 0L, bytes, flags);
				if (ptr == 0L) {
					throw new IllegalStateException("glMapBufferRange returned null");
				}
				mappedPtrs[i] = ptr;
				MemoryUtil.memSet(ptr, 0, bytes);
			}
		} finally {
			GL33C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
		}
		shadowPtr = MemoryUtil.nmemCalloc(1L, bytes);
		if (shadowPtr == 0L) {
			throw new IllegalStateException("out of memory for batch shadow");
		}
		ringBytes = bytes;
		frameSlot = 0;
		entryCount = 0;
		// Keep binding slot backed by a valid zeroed UBO even for non-Single-MDI terrain draws.
		GlStateCache.invalidateUniformBinding(BATCH_UBO_BINDING);
		GL33C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, BATCH_UBO_BINDING, uboHandles[0], 0L, bytes);
		ready = true;
	}

	/**
	 * Binds a zeroed, full-size block to {@link #BATCH_UBO_BINDING}. Base instance 0 never reads
	 * it, but an active uniform block with no buffer behind it is undefined behaviour, which
	 * the no-error context would hide.
	 */
	private void bindFallbackUbo() {
		GpuDevice d = RenderSystem.tryGetDevice();
		if (d == null) {
			return;
		}
		if (d != fallbackDevice) {
			// A previous device's buffer died with its context.
			fallbackDevice = d;
			fallbackUbo = 0;
		}
		long bytes = (long) maxEntries << 4;
		if (fallbackUbo == 0) {
			int h = GL33C.glGenBuffers();
			long zeros = MemoryUtil.nmemCalloc(1L, bytes);
			try {
				GL33C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, h);
				GL15C.nglBufferData(GL31C.GL_UNIFORM_BUFFER, bytes, zeros, GL15C.GL_STATIC_DRAW);
			} finally {
				GL33C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
				MemoryUtil.nmemFree(zeros);
			}
			fallbackUbo = h;
		}
		GlStateCache.invalidateUniformBinding(BATCH_UBO_BINDING);
		GL33C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, BATCH_UBO_BINDING, fallbackUbo, 0L, bytes);
	}

	private void fail(Throwable t) {
		failed = true;
		LOGGER.warn("Ephemera OpenGL single multi-draw terrain disabled: {}", t.toString());
		release();
	}

	/** Unmaps and deletes the ring (dispatcher disposal); the next frame re-initializes it. */
	public void release() {
		if (device != null && device == RenderSystem.tryGetDevice()) {
			for (int i = 0; i < FRAMES; i++) {
				int h = uboHandles[i];
				if (h == 0) {
					continue;
				}
				try {
					if (mappedPtrs[i] != 0L) {
						GL33C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, h);
						GL15C.glUnmapBuffer(GL31C.GL_UNIFORM_BUFFER);
						GL33C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
					}
					GL33C.glDeleteBuffers(h);
				} catch (Throwable t) {
					LOGGER.debug("Ephemera OpenGL single multi-draw buffer release failed: {}", t.toString());
				}
			}
			forgetBuffers();
			if (!batchPrograms.isEmpty()) {
				try {
					bindFallbackUbo();
				} catch (Throwable t) {
					LOGGER.debug("Ephemera OpenGL single multi-draw fallback block bind failed: {}", t.toString());
				}
			}
			return;
		}
		forgetBuffers();
	}

	private void forgetBuffers() {
		java.util.Arrays.fill(uboHandles, 0);
		java.util.Arrays.fill(mappedPtrs, 0L);
		if (shadowPtr != 0L) {
			MemoryUtil.nmemFree(shadowPtr);
			shadowPtr = 0L;
		}
		ready = false;
		ringBytes = 0L;
		entryCount = 0;
		frameSlot = 0;
	}
}
