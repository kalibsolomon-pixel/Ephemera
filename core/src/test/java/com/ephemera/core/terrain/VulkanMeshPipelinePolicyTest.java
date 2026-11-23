package com.ephemera.core.terrain;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class VulkanMeshPipelinePolicyTest {
	private static final VulkanMeshPipelinePolicy.VisualKey DEFAULT_VIS =
		new VulkanMeshPipelinePolicy.VisualKey(true, true, 1.0f, true);
	private static final VulkanMeshPipelinePolicy.VisualKey ALT_VIS =
		new VulkanMeshPipelinePolicy.VisualKey(false, true, 1.0f, false);

	@Test
	void buildsBothPipelinesBeforeReportingReadyAndSwapsAtomicallyOnVisualChange() {
		VulkanMeshPipelinePolicy policy = new VulkanMeshPipelinePolicy();
		List<Long> destroyed = new ArrayList<>();
		long[] nextId = {100L};

		assertFalse(policy.isReadyFor(44, 126, DEFAULT_VIS));
		assertTrue(policy.ensureReady(44, 126, DEFAULT_VIS, false,
			(cf, df, vk) -> new VulkanMeshPipelinePolicy.PipelinePair(nextId[0]++, nextId[0]++),
			destroyed::add));
		assertTrue(policy.isReadyFor(44, 126, DEFAULT_VIS));
		assertEquals(100L, policy.solidPipeline());
		assertEquals(101L, policy.cutoutPipeline());
		assertTrue(destroyed.isEmpty());

		// Steady frame with same formats and visuals does not rebuild.
		assertTrue(policy.ensureReady(44, 126, DEFAULT_VIS, false,
			(cf, df, vk) -> { throw new AssertionError("Should not rebuild unchanged pipelines"); },
			destroyed::add));

		// Visual settings change builds both replacements first and then destroys old pair.
		assertTrue(policy.ensureReady(44, 126, ALT_VIS, false,
			(cf, df, vk) -> new VulkanMeshPipelinePolicy.PipelinePair(nextId[0]++, nextId[0]++),
			destroyed::add));
		assertEquals(102L, policy.solidPipeline());
		assertEquals(103L, policy.cutoutPipeline());
		assertEquals(List.of(100L, 101L), destroyed);
	}

	@Test
	void forcedPipelineFailureTriggersSameFrameFallbackAndCleansPartialHandles() {
		VulkanMeshPipelinePolicy policy = new VulkanMeshPipelinePolicy();
		List<Long> destroyed = new ArrayList<>();

		// Forced failure before handing terrain to mesh engine returns false immediately.
		assertFalse(policy.ensureReady(44, 126, DEFAULT_VIS, true,
			(cf, df, vk) -> new VulkanMeshPipelinePolicy.PipelinePair(1L, 2L),
			destroyed::add));
		assertTrue(policy.hasFailed());
		assertFalse(policy.isReadyFor(44, 126, DEFAULT_VIS));
		assertEquals(0L, policy.solidPipeline());
		assertEquals(0L, policy.cutoutPipeline());

		// Partial handle (solid valid, cutout invalid = 0) destroys the solid handle and fails cleanly.
		policy.clearFailureForTest();
		assertFalse(policy.ensureReady(44, 126, DEFAULT_VIS, false,
			(cf, df, vk) -> new VulkanMeshPipelinePolicy.PipelinePair(77L, 0L),
			destroyed::add));
		assertTrue(policy.hasFailed());
		assertEquals(List.of(77L), destroyed);
	}

	@Test
	void systemPropertyForcedFailurePreventsMeshActivation() {
		String prev = System.getProperty(VulkanMeshPipelinePolicy.FORCE_FAILURE_PROPERTY);
		try {
			System.setProperty(VulkanMeshPipelinePolicy.FORCE_FAILURE_PROPERTY, "true");
			VulkanMeshPipelinePolicy policy = new VulkanMeshPipelinePolicy();
			assertFalse(policy.ensureReady(44, 126, DEFAULT_VIS, false,
				(cf, df, vk) -> new VulkanMeshPipelinePolicy.PipelinePair(10L, 11L),
				h -> {}));
			assertTrue(policy.hasFailed());
		} finally {
			if (prev == null) {
				System.clearProperty(VulkanMeshPipelinePolicy.FORCE_FAILURE_PROPERTY);
			} else {
				System.setProperty(VulkanMeshPipelinePolicy.FORCE_FAILURE_PROPERTY, prev);
			}
		}
	}
}
