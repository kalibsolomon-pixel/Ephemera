package com.ephemera.core.visuals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DynamicLightGridTest {
	@Test
	void emptyGridReturnsZero() {
		DynamicLightGrid grid = new DynamicLightGrid(8);
		assertTrue(grid.isEmpty());
		assertEquals(0.0f, grid.lightAt(0, 0, 0));
	}

	@Test
	void falloffPeaksAtSourceAndVanishesAtReach() {
		assertEquals(14.0f, DynamicLightGrid.falloff(0.0, 14), 1e-5f);
		assertEquals(0.0f, DynamicLightGrid.falloff(14.0 * 14.0, 14), 1e-6f);
		assertEquals(0.0f, DynamicLightGrid.falloff(400.0, 14));
		assertEquals(0.0f, DynamicLightGrid.falloff(0.0, 0));
	}

	@Test
	void falloffIsQuadraticAndMonotonic() {
		// halfway to the edge: L * (1 - 0.5)^2
		assertEquals(14.0f * 0.25f, DynamicLightGrid.falloff(49.0, 14), 1e-5f);
		float prev = Float.MAX_VALUE;
		for (int i = 0; i <= 140; i++) {
			double d = i / 10.0;
			float v = DynamicLightGrid.falloff(d * d, 14);
			assertTrue(v <= prev + 1e-6f, "monotonic at d=" + d);
			prev = v;
		}
	}

	@Test
	void lookupFindsNearbyEmitterAndIgnoresFarOnes() {
		DynamicLightGrid grid = new DynamicLightGrid(8);
		assertTrue(grid.add(10.5, 64.0, 10.5, 14));
		assertEquals(14.0f, grid.lightAt(10.5, 64.0, 10.5), 1e-5f);
		assertTrue(grid.lightAt(13.5, 64.0, 10.5) > 0.0f);
		assertEquals(0.0f, grid.lightAt(60.0, 64.0, 10.5));
		assertEquals(0.0f, grid.lightAt(10.5, 100.0, 10.5));
	}

	@Test
	void lookupCrossesCellBoundariesAndNegativeCoordinates() {
		DynamicLightGrid grid = new DynamicLightGrid(8);
		grid.add(-0.5, -0.5, -0.5, 15);
		// 1 block away, across the cell boundary at 0
		assertEquals(DynamicLightGrid.falloff(1.0, 15), grid.lightAt(0.5, -0.5, -0.5), 1e-5f);
		grid.clear();
		grid.add(15.9, 5.0, 5.0, 15);
		assertEquals(DynamicLightGrid.falloff(0.04, 15), grid.lightAt(16.1, 5.0, 5.0), 1e-4f);
	}

	@Test
	void strongestEmitterWinsAndSharedCellsAreChained() {
		DynamicLightGrid grid = new DynamicLightGrid(8);
		grid.add(1.0, 1.0, 1.0, 5);
		grid.add(2.0, 1.0, 1.0, 15);
		grid.add(3.0, 1.0, 1.0, 8);
		float expected = DynamicLightGrid.falloff(0.0, 15);
		assertEquals(expected, grid.lightAt(2.0, 1.0, 1.0), 1e-5f);
	}

	@Test
	void capacityAndLevelValidation() {
		DynamicLightGrid grid = new DynamicLightGrid(2);
		assertFalse(grid.add(0, 0, 0, 0));
		assertFalse(grid.add(0, 0, 0, -4));
		assertTrue(grid.add(0, 0, 0, 40)); // clamped to 15
		assertTrue(grid.add(1, 0, 0, 3));
		assertFalse(grid.add(2, 0, 0, 3));
		assertEquals(2, grid.size());
		assertEquals(15.0f, grid.lightAt(0, 0, 0), 1e-5f);
	}

	@Test
	void clearResetsEverything() {
		DynamicLightGrid grid = new DynamicLightGrid(4);
		grid.add(0, 0, 0, 15);
		grid.clear();
		assertTrue(grid.isEmpty());
		assertEquals(0.0f, grid.lightAt(0, 0, 0));
		assertTrue(grid.add(5, 5, 5, 10));
		assertTrue(grid.lightAt(5, 5, 5) > 9.9f);
	}

	@Test
	void manyEmittersAcrossDistinctCellsStayQueryable() {
		DynamicLightGrid grid = new DynamicLightGrid(256);
		for (int i = 0; i < 256; i++) {
			grid.add(i * 40.0, 70.0, (i % 7) * 40.0, 12);
		}
		for (int i = 0; i < 256; i++) {
			assertEquals(12.0f, grid.lightAt(i * 40.0, 70.0, (i % 7) * 40.0), 1e-5f, "emitter " + i);
		}
		assertEquals(0.0f, grid.lightAt(20.0, 200.0, 20.0));
	}
}
