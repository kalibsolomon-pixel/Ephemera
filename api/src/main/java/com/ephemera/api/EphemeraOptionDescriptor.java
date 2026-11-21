package com.ephemera.api;

/**
 * Metadata and live value for one toggleable Ephemera engine option exposed to
 * companion UIs such as Aurora Client's Settings screen.
 */
public record EphemeraOptionDescriptor(
	String key,
	String label,
	String description,
	String category,
	boolean value,
	boolean requiresRestart
) {}
