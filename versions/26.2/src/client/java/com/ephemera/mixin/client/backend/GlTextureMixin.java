package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.GlTextureMipState;
import com.mojang.blaze3d.opengl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import static org.lwjgl.opengl.GL12C.GL_TEXTURE_BASE_LEVEL;

@Mixin(GlTexture.class)
abstract class GlTextureMixin implements GlTextureMipState {
	@Unique private long ephemera$mipGeneration = -1;
	@Unique private int ephemera$baseMip = -1;
	@Unique private int ephemera$maxMip = -1;

	@Override
	public boolean ephemera$hasMipParameter(int parameter, int level, long generation) {
		return ephemera$mipGeneration == generation
			&& (parameter == GL_TEXTURE_BASE_LEVEL ? ephemera$baseMip : ephemera$maxMip) == level;
	}

	@Override
	public void ephemera$recordMipParameter(int parameter, int level, long generation) {
		if (ephemera$mipGeneration != generation) {
			ephemera$mipGeneration = generation;
			ephemera$baseMip = -1;
			ephemera$maxMip = -1;
		}
		if (parameter == GL_TEXTURE_BASE_LEVEL) {
			ephemera$baseMip = level;
		} else {
			ephemera$maxMip = level;
		}
	}
}
