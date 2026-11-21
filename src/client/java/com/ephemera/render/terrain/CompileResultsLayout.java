package com.ephemera.render.terrain;

import org.jspecify.annotations.Nullable;

/** Duck interface mixed into {@code SectionCompiler.Results}: carries the baked {@link SectionLayout} to the mesh. */
public interface CompileResultsLayout {
	@Nullable SectionLayout ephemera$layout();

	void ephemera$setLayout(@Nullable SectionLayout layout);
}
