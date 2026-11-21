package com.ephemera.render.gui;

/**
 * Mixed into {@code PatchedDataComponentMap}: an identity token for the map's current patch contents.
 * <p>
 * Every mutation of a patched map goes through {@code ensureMapOwnership}, which replaces the patch map when
 * {@code copyOnWrite} is set. Arming {@code copyOnWrite} and remembering the patch reference therefore
 * detects any later change of the stack's components with one reference comparison, without copying the
 * stack or walking the components.
 */
public interface GuiPatchSnapshot {
	/** The current patch reference, without arming anything; only meaningful against an earlier armed token. */
	Object ephemera$patchToken();

	/** Arms copy-on-write (so the next mutation replaces the patch) and returns the current patch reference. */
	Object ephemera$armPatchToken();
}
