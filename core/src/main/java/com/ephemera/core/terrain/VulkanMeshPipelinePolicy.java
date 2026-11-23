package com.ephemera.core.terrain;

import java.util.Objects;

/**
 * Pure-Java readiness and atomic-swap state machine for Vulkan mesh-shader graphics pipelines.
 *
 * <p>Guarantees that opaque terrain is only handed to the mesh-shader path once BOTH the SOLID and
 * CUTOUT pipelines exist for the active attachment formats and visual options. When a pipeline
 * build fails (either on initial world entry or after a visual-settings/format change), the caller
 * learns immediately before draw planning so the indexed fallback path renders every section on the
 * exact same frame with zero missing chunks.
 */
public final class VulkanMeshPipelinePolicy {
	public record VisualKey(boolean smoothFog, boolean dither, float ditherStrength, boolean horizon, boolean heldWorldLight) {
		public VisualKey(boolean smoothFog, boolean dither, float ditherStrength, boolean horizon) {
			this(smoothFog, dither, ditherStrength, horizon, false);
		}
	}

	public record PipelinePair(long solidPipeline, long cutoutPipeline) {
		public boolean valid() {
			return solidPipeline != 0L && cutoutPipeline != 0L;
		}
	}

	@FunctionalInterface
	public interface PipelineBuilder {
		PipelinePair build(int colorFormat, int depthFormat, VisualKey visualKey);
	}

	@FunctionalInterface
	public interface PipelineDestroyer {
		void destroy(long pipelineHandle);
	}

	public static final String FORCE_FAILURE_PROPERTY = "ephemera.test.forceMeshPipelineFailure";

	private long solidPipeline;
	private long cutoutPipeline;
	private int compiledColorFormat;
	private int compiledDepthFormat;
	private VisualKey compiledVisual;
	private boolean failed;

	public boolean isReadyFor(int colorFormat, int depthFormat, VisualKey visualKey) {
		return !failed
			&& solidPipeline != 0L
			&& cutoutPipeline != 0L
			&& colorFormat != 0
			&& depthFormat != 0
			&& colorFormat == compiledColorFormat
			&& depthFormat == compiledDepthFormat
			&& Objects.equals(compiledVisual, visualKey);
	}

	public boolean hasFailed() {
		return failed;
	}

	public long solidPipeline() {
		return solidPipeline;
	}

	public long cutoutPipeline() {
		return cutoutPipeline;
	}

	public void resetDeviceState() {
		solidPipeline = 0L;
		cutoutPipeline = 0L;
		compiledColorFormat = 0;
		compiledDepthFormat = 0;
		compiledVisual = null;
	}

	public void clearFailureForTest() {
		failed = false;
		resetDeviceState();
	}

	/**
	 * Ensures both SOLID and CUTOUT pipelines are built for {@code (colorFormat, depthFormat, visualKey)}.
	 * Builds the replacement pair FIRST; old handles are only queued for destruction after BOTH new
	 * handles are valid. Any failure marks the policy failed and returns {@code false} so the caller
	 * falls back to indexed draws on the same frame.
	 */
	public boolean ensureReady(int colorFormat, int depthFormat, VisualKey visualKey,
							   boolean forceFailure, PipelineBuilder builder, PipelineDestroyer destroyer) {
		if (failed || colorFormat == 0 || depthFormat == 0 || visualKey == null) {
			return false;
		}
		if (isReadyFor(colorFormat, depthFormat, visualKey)) {
			return true;
		}
		PipelinePair built = null;
		try {
			if (forceFailure || Boolean.getBoolean(FORCE_FAILURE_PROPERTY)) {
				throw new IllegalStateException("Forced Vulkan mesh pipeline creation failure (" + FORCE_FAILURE_PROPERTY + ")");
			}
			built = builder.build(colorFormat, depthFormat, visualKey);
			if (built == null || !built.valid()) {
				throw new IllegalStateException("Vulkan mesh pipeline builder returned invalid handles");
			}
			long oldSolid = solidPipeline;
			long oldCutout = cutoutPipeline;
			solidPipeline = built.solidPipeline();
			cutoutPipeline = built.cutoutPipeline();
			compiledColorFormat = colorFormat;
			compiledDepthFormat = depthFormat;
			compiledVisual = visualKey;
			if (oldSolid != 0L) {
				destroyer.destroy(oldSolid);
			}
			if (oldCutout != 0L) {
				destroyer.destroy(oldCutout);
			}
			return true;
		} catch (Throwable t) {
			if (built != null) {
				if (built.solidPipeline() != 0L) {
					destroyer.destroy(built.solidPipeline());
				}
				if (built.cutoutPipeline() != 0L) {
					destroyer.destroy(built.cutoutPipeline());
				}
			}
			failed = true;
			return false;
		}
	}
}
