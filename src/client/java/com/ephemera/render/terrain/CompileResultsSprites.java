package com.ephemera.render.terrain;

/**
 * Duck interface mixed into {@code SectionCompiler.Results}; carries the animated sprite
 * ids gathered while compiling a section to the {@code CompiledSectionMesh} built from it.
 */
public interface CompileResultsSprites {
	int[] ephemera$animatedSprites();

	int ephemera$spriteGeneration();

	boolean ephemera$untrackedSprites();

	void ephemera$setSprites(int[] ids, int generation, boolean untracked);
}
