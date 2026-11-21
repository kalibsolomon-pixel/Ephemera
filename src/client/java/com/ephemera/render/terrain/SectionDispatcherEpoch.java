package com.ephemera.render.terrain;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Duck interface mixed into {@code SectionRenderDispatcher}. Every dispatcher gets a
 * unique positive epoch when its uber buffers were successfully tagged; zero means the
 * fast path is unavailable for that dispatcher.
 */
public interface SectionDispatcherEpoch {
	AtomicInteger NEXT_EPOCH = new AtomicInteger(1);

	int ephemera$epoch();
}
