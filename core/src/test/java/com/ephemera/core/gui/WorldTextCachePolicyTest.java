package com.ephemera.core.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldTextCachePolicyTest {
	@Test
	void slotIndexIsInRangeAndSensitiveToAllInputs() {
		int base = WorldTextCachePolicy.slotIndex(12345, Float.floatToRawIntBits(-20.0f), 0, -1, 0x40000000, 0);
		assertTrue(base >= 0 && base < WorldTextCachePolicy.SLOT_COUNT);
		assertEquals(base, WorldTextCachePolicy.slotIndex(12345, Float.floatToRawIntBits(-20.0f), 0, -1, 0x40000000, 0));

		// Different identity / color / shadow flags should distribute across slots
		int diffId = WorldTextCachePolicy.slotIndex(12346, Float.floatToRawIntBits(-20.0f), 0, -1, 0x40000000, 0);
		int diffColor = WorldTextCachePolicy.slotIndex(12345, Float.floatToRawIntBits(-20.0f), 0, 0xFF00FF00, 0x40000000, 0);
		int diffFlags = WorldTextCachePolicy.slotIndex(12345, Float.floatToRawIntBits(-20.0f), 0, -1, 0x40000000,
			WorldTextCachePolicy.packFlags(true, false));
		assertNotEquals(base, diffId);
		assertNotEquals(base, diffColor);
		assertNotEquals(base, diffFlags);
	}

	@Test
	void freshnessEnforcesMaxIdleFrames() {
		assertTrue(WorldTextCachePolicy.isFresh(100L, 100L));
		assertTrue(WorldTextCachePolicy.isFresh(356L, 100L));
		assertFalse(WorldTextCachePolicy.isFresh(357L, 100L));
		assertFalse(WorldTextCachePolicy.isFresh(99L, 100L));
	}
}
