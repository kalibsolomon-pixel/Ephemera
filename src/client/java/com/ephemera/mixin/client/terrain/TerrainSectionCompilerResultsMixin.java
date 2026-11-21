package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.CompileResultsLayout;
import com.ephemera.render.terrain.CompileResultsSprites;
import com.ephemera.render.terrain.SectionLayout;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries a compiled section's animated sprite ids and baked vertex layout from the compiler to its mesh. */
@Mixin(SectionCompiler.Results.class)
public abstract class TerrainSectionCompilerResultsMixin implements CompileResultsSprites, CompileResultsLayout {
	@Unique
	private static final int[] EPHEMERA$NO_SPRITES = new int[0];

	@Unique
	private int[] ephemera$sprites = EPHEMERA$NO_SPRITES;
	@Unique
	private int ephemera$generation = -1;
	@Unique
	private boolean ephemera$untracked;
	@Unique
	private @Nullable SectionLayout ephemera$layout;

	@Override
	public int[] ephemera$animatedSprites() {
		return ephemera$sprites;
	}

	@Override
	public int ephemera$spriteGeneration() {
		return ephemera$generation;
	}

	@Override
	public boolean ephemera$untrackedSprites() {
		return ephemera$untracked;
	}

	@Override
	public void ephemera$setSprites(int[] ids, int generation, boolean untracked) {
		ephemera$sprites = ids;
		ephemera$generation = generation;
		ephemera$untracked = untracked;
	}

	@Override
	public @Nullable SectionLayout ephemera$layout() {
		return ephemera$layout;
	}

	@Override
	public void ephemera$setLayout(@Nullable SectionLayout layout) {
		ephemera$layout = layout;
	}
}
