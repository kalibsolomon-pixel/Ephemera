package com.ephemera.render.backend;

public interface GlTextureMipState {
	boolean ephemera$hasMipParameter(int parameter, int level, long generation);
	void ephemera$recordMipParameter(int parameter, int level, long generation);
}
