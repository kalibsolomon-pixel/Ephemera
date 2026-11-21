package com.ephemera.core.lighting;

import java.util.NoSuchElementException;
import java.util.ArrayDeque;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FastLightQueueTest {
    @Test void interleavedUpdatesMatchReferenceQueue() {
        FastLightQueue actual = new FastLightQueue();
        ArrayDeque<Long> expected = new ArrayDeque<>();
        Random random = new Random(26_02);
        for (int i = 0; i < 100000; i++) {
            if (expected.isEmpty() || random.nextBoolean()) {
                long payload = random.nextLong();
                expected.addLast(payload);
                actual.add(payload);
            } else {
                assertEquals(expected.removeFirst().longValue(), actual.remove());
            }
            assertEquals(expected.isEmpty(), actual.isEmpty());
        }
        while (!expected.isEmpty()) assertEquals(expected.removeFirst().longValue(), actual.remove());
        assertTrue(actual.isEmpty());
    }

    @Test void wrapGrowthDrainAndReusePreserveFullWords() {
        FastLightQueue queue = new FastLightQueue();
        for (int wave = 0; wave < 3; wave++) {
            for (int i = 0; i < 31; i++) queue.add(~(long) i);
            for (int i = 0; i < 30; i++) assertEquals(~(long) i, queue.remove());
            for (int i = 0; i < 65536; i++) queue.add(Long.MIN_VALUE + i);
            assertEquals(~30L, queue.remove());
            for (int i = 0; i < 65536; i++) assertEquals(Long.MIN_VALUE + i, queue.remove());
            assertTrue(queue.isEmpty());
            assertThrows(NoSuchElementException.class, queue::remove);
        }
    }

    @Test void localPositionAndLevelRoundTrip() {
        for (int word = 0; word < 65536; word++) {
            assertEquals(word, FastLightQueue.packLocal(word, word >> 8, word >> 4, word >> 12));
        }
    }
}
