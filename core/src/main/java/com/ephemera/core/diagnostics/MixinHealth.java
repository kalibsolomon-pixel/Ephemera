package com.ephemera.core.diagnostics;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks applied, stood-down (yielded to another mod), and failed mixins
 * with a zero-overhead fast path when all mixins are healthy.
 */
public final class MixinHealth {
	private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
	private static final Map<String, String> STOOD_DOWN = new ConcurrentHashMap<>();
	private static final Map<String, String> FAILED = new ConcurrentHashMap<>();
	private static volatile boolean anyFailed = false;

	private MixinHealth() {}

	public static void recordApplied(String mixinSimpleName) {
		APPLIED.add(mixinSimpleName);
	}

	public static void recordStoodDown(String mixinSimpleName, String ownerModId) {
		STOOD_DOWN.put(mixinSimpleName, ownerModId);
	}

	public static void recordFailed(String mixinSimpleName, String reason) {
		FAILED.put(mixinSimpleName, reason);
		anyFailed = true;
	}

	public static boolean hasFailed(String mixinSimpleName) {
		return anyFailed && FAILED.containsKey(mixinSimpleName);
	}

	/** Zero-allocation applied check for hot paths (unlike {@link #applied()}, which wraps a set). */
	public static boolean isApplied(String mixinSimpleName) {
		return APPLIED.contains(mixinSimpleName);
	}

	public static boolean hasAnyFailure() {
		return anyFailed;
	}

	public static Set<String> applied() {
		return Collections.unmodifiableSet(APPLIED);
	}

	public static Map<String, String> stoodDown() {
		return Collections.unmodifiableMap(STOOD_DOWN);
	}

	public static Map<String, String> failed() {
		return Collections.unmodifiableMap(FAILED);
	}

	public static String summary() {
		return "Ephemera mixins: " + APPLIED.size() + " applied, "
			+ STOOD_DOWN.size() + " stood down, " + FAILED.size() + " failed";
	}
}
