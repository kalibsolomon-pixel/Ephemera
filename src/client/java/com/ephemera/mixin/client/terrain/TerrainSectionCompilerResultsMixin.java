package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.CompileResultsSprites;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries a compiled section's animated sprite ids from the compiler to its mesh. */
@Mixin(SectionCompiler.Results.class)
public abstract class TerrainSectionCompilerResultsMixin implements CompileResultsSprites {
	@Unique
	private static final int[] EPHEMERA$NO_SPRITES = new int[0];

	@Unique
	private int[] ephemera$sprites = EPHEMERA$NO_SPRITES;
	@Unique
	private int ephemera$generation = -1;
	@Unique
	private boolean ephemera$untracked;

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
}
