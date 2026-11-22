package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.TerrainTopology;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bumps {@link TerrainTopology#dirtySections()} whenever any {@code SectionDirtyState} becomes
 * dirty, enabling {@link LevelExtractorSectionUpdatesMixin} to skip the O(visibleSections) dirty
 * scan and {@code new RenderRegionCache()} allocation in O(1) on steady frames.
 */
@Mixin(targets = "net.minecraft.client.SectionUpdateTracker$SectionDirtyState")
abstract class SectionDirtyStateEpochMixin {
	@Shadow private boolean isDirty;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$onCreateDirty(CallbackInfo ci) {
		if (this.isDirty) {
			TerrainTopology.bumpDirtySections();
		}
	}

	@Inject(method = "setDirty(Z)V", at = @At("TAIL"))
	private void ephemera$onSetDirty(boolean fromPlayer, CallbackInfo ci) {
		TerrainTopology.bumpDirtySections();
	}

	@Inject(method = "setSectionNode(J)V", at = @At("TAIL"))
	private void ephemera$onReassignNode(long sectionNode, CallbackInfo ci) {
		if (this.isDirty) {
			TerrainTopology.bumpDirtySections();
		}
	}
}
