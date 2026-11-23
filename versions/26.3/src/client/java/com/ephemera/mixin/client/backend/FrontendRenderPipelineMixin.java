package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.FrontendPipelineUniforms;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builds a compact parallel array of {@code (name, slot)} pairs once when each immutable
 * {@link FrontendRenderPipeline} is constructed (typically 3-6 uniforms per pipeline).
 */
@Mixin(FrontendRenderPipeline.class)
abstract class FrontendRenderPipelineMixin implements FrontendPipelineUniforms {
	@Unique private static final String[] EMPTY_NAMES = new String[0];
	@Unique private static final int[] EMPTY_SLOTS = new int[0];

	@Shadow @Final private Object2IntMap<String> uniformIndices;

	@Unique private String[] ephemera$uNames = EMPTY_NAMES;
	@Unique private int[] ephemera$uSlots = EMPTY_SLOTS;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$cacheUniformTable(CallbackInfo ci) {
		int n = uniformIndices != null ? uniformIndices.size() : 0;
		if (n == 0) {
			return;
		}
		String[] names = new String[n];
		int[] slots = new int[n];
		int i = 0;
		for (Object2IntMap.Entry<String> e : uniformIndices.object2IntEntrySet()) {
			names[i] = e.getKey();
			slots[i] = e.getIntValue();
			i++;
		}
		ephemera$uNames = names;
		ephemera$uSlots = slots;
	}

	@Override
	public String[] ephemera$uniformNames() {
		return ephemera$uNames;
	}

	@Override
	public int[] ephemera$uniformSlots() {
		return ephemera$uSlots;
	}

	@Override
	public int ephemera$findSlot(String name) {
		String[] names = ephemera$uNames;
		int len = names.length;
		// Fast identity pass: Minecraft uniform names are compile-time string literals.
		for (int i = 0; i < len; i++) {
			if (names[i] == name) {
				return ephemera$uSlots[i];
			}
		}
		for (int i = 0; i < len; i++) {
			if (names[i].equals(name)) {
				return ephemera$uSlots[i];
			}
		}
		return -1;
	}
}
