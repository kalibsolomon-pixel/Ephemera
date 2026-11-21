package com.ephemera.core.gui;

import java.util.List;

/**
 * Hash-caching stand-in for the model-identity {@code List} that {@code GuiItemAtlas},
 * {@code GuiRenderState} and the oversized-item renderers use as a map/set key. Equality and hash code are
 * exactly those of the wrapped list, but the list is walked once per change instead of on every lookup,
 * and a lookup with the very same key instance is an identity hit.
 * <p>
 * The key also carries one memo (an owner and the value resolved for it) so a caller can skip a map probe
 * when it asks the same owner again; {@link #elementAppended()} drops both the hash and the memo.
 */
public final class ModelIdentityKey {
	private final List<Object> elements;
	private int hash;
	private boolean hashed;
	private Object memoOwner;
	private Object memoValue;

	public ModelIdentityKey(List<Object> elements) {
		this.elements = elements;
	}

	public void elementAppended() {
		hashed = false;
		memoOwner = null;
		memoValue = null;
	}

	/** @return the value remembered for {@code owner}, or null if the memo belongs to someone else */
	public Object memoFor(Object owner) {
		return memoOwner == owner ? memoValue : null;
	}

	public void remember(Object owner, Object value) {
		memoOwner = owner;
		memoValue = value;
	}

	@Override
	public int hashCode() {
		if (!hashed) {
			hash = elements.hashCode();
			hashed = true;
		}
		return hash;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		return other instanceof ModelIdentityKey key && hashCode() == key.hashCode() && elements.equals(key.elements);
	}

	@Override
	public String toString() {
		return elements.toString();
	}
}
