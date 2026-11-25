package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.TerrainTopology;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Maintains a rolling content digest of the freshly refilled {@code visibleSections} list as
 * {@code applyFrustum}'s octree visitor appends each section (the first {@code List.add} in
 * {@code lambda$addSectionsInFrustum$0}; the second appends {@code nearbyVisibleSections} and is
 * intentionally not digested). Two independent 64-bit rolled mixes over the packed section node
 * (~2 ALU ops per appended section inside work vanilla already does) let the extractor's
 * clean-frame skip compare list content across frames instead of the per-refill epoch, which
 * {@code clearVisibleSections} bumps on every quantized-rotation frame even when the visible set
 * is content-identical — re-enabling the full 2,000+-section dirty walk exactly on camera-flick
 * frames. Digests hash the packed node (never object identity: {@code RotatingSectionStorage}
 * recycles {@code RenderSection} instances in place via {@code setSectionNode}).
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphDigestMixin {
	@WrapOperation(
		method = "lambda$addSectionsInFrustum$0",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/List;add:(Ljava/lang/Object;)Z",
			ordinal = 0
		)
	)
	private boolean ephemera$digestVisibleAppend(List<?> visible, Object section, Operation<Boolean> original) {
		long node = section instanceof SectionRenderDispatcher.RenderSection render ? render.getSectionNode() : 0L;
		TerrainTopology.visibleSectionsDigest1 = TerrainTopology.visibleSectionsDigest1 * 0x9E3779B97F4A7C15L + node;
		TerrainTopology.visibleSectionsDigest2 = (TerrainTopology.visibleSectionsDigest2 ^ Long.rotateLeft(node, 31)) * 0xFF51AFD7ED558CCDL;
		TerrainTopology.visibleSectionsDigestSize++;
		return original.call(visible, section);
	}
}
